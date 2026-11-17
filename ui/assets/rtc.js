// ---- Video call core, shared by the parent page (call.js) and the child phone's call screen (ui/call.html) ----
// Low bandwidth on purpose (docs/flows/call.md): 320x240 at 15 fps capped at 150 kbps, Opus mono 24 kbps with DTX
// (silence sends almost nothing) and FEC; picture turns off by itself on a weak link (sound stays). Non-trickle ICE:
// one SDP each way through the relay, nothing else. Media goes straight between the two devices (TURN only when needed).
const RTC = (() => {
  const VIDEO_BPS = 150e3, AUDIO_BPS = 24e3, LOW_BPS = 90e3;
  const want = (video) => ({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
    video: video ? { facingMode: "user", width: { ideal: 320 }, height: { ideal: 240 }, frameRate: { ideal: 15, max: 15 } } : false,
  });
  // our SDP asks the other side's encoder for: Opus mono, 24 kbps, DTX, FEC, 16 kHz; video at most VIDEO_BPS (b=AS, kbps)
  function tune(sdp) {
    const pt = (sdp.match(/a=rtpmap:(\d+) opus\/48000/i) || [])[1];
    if (pt) sdp = sdp.replace(new RegExp(`a=fmtp:${pt} ([^\\r\\n]*)`), (m, p) => {
      const o = {};
      for (const kv of p.split(";")) { const [k, v] = kv.trim().split("="); if (k) o[k] = v; }
      Object.assign(o, { stereo: "0", "sprop-stereo": "0", useinbandfec: "1", usedtx: "1", maxaveragebitrate: String(AUDIO_BPS), maxplaybackrate: "16000" });
      return `a=fmtp:${pt} ` + Object.entries(o).map(([k, v]) => k + "=" + v).join(";");
    });
    const L = sdp.split("\r\n"), out = [];
    let vid = false, capped = false;
    for (const l of L) {
      if (l.startsWith("m=")) { vid = l.startsWith("m=video"); capped = false; }
      if (vid && l.startsWith("b=")) continue;   // ours replaces any
      out.push(l);
      if (vid && !capped && l.startsWith("c=")) { out.push("b=AS:" + VIDEO_BPS / 1000); capped = true; }
    }
    return out.join("\r\n");
  }
  // the senders' own caps (Chrome honours these even when the other side ignores b=AS)
  async function cap(pc) {
    for (const s of pc.getSenders()) {
      if (!s.track) continue;
      try {
        const p = s.getParameters();
        if (!p.encodings || !p.encodings.length) p.encodings = [{}];
        p.encodings[0].maxBitrate = s.track.kind === "video" ? VIDEO_BPS : AUDIO_BPS;
        if (s.track.kind === "video") { p.encodings[0].maxFramerate = 15; p.degradationPreference = "balanced"; }
        await s.setParameters(p);
      } catch {}
    }
  }
  const gathered = (pc, ms) => new Promise(res => {
    if (pc.iceGatheringState === "complete") return res();
    const t = setTimeout(res, ms);
    pc.addEventListener("icegatheringstatechange", () => { if (pc.iceGatheringState === "complete") { clearTimeout(t); res(); } });
  });
  async function media(video) {
    try { return await navigator.mediaDevices.getUserMedia(want(video)); }
    catch (e) { if (!video) throw e; return navigator.mediaDevices.getUserMedia(want(false)); }   // no camera / camera refused: sound only
  }

  /**
   * o: { ice, offer?: the caller's SDP (we answer), video: bool, local: <video>, remote: <video>, onState(s), onMsg(m), onNet(n) }
   * -> call { sdp (send it), accept(answerSdp), send(msg), mute(on), camera(on), stats(), close() }.
   * onState: "connecting" | "connected" | "disconnected" | "failed" | "closed". onNet: { low, relay, kbps, mb }.
   */
  async function open(o) {
    const stream = await media(o.video !== false);
    const pc = new RTCPeerConnection({ iceServers: o.ice || [], bundlePolicy: "max-bundle", rtcpMuxPolicy: "require" });
    for (const t of stream.getTracks()) pc.addTrack(t, stream);
    if (o.local) { o.local.muted = true; o.local.srcObject = stream; }
    pc.ontrack = (e) => { if (o.remote && e.streams[0] && o.remote.srcObject !== e.streams[0]) o.remote.srcObject = e.streams[0]; };
    let dc = null, closed = false, lowN = 0, autoOff = false, camOn = stream.getVideoTracks().length > 0, net = { low: false, relay: false, kbps: 0, mb: 0 };
    const wire = (ch) => { dc = ch; ch.onmessage = (e) => { try { o.onMsg && o.onMsg(JSON.parse(e.data)); } catch {} }; };
    if (o.offer) pc.ondatachannel = (e) => wire(e.channel); else wire(pc.createDataChannel("kg"));
    pc.onconnectionstatechange = () => o.onState && o.onState(pc.connectionState);
    if (o.offer) await pc.setRemoteDescription({ type: "offer", sdp: o.offer });
    const d = o.offer ? await pc.createAnswer() : await pc.createOffer();
    await pc.setLocalDescription({ type: d.type, sdp: tune(d.sdp) });
    await cap(pc);
    await gathered(pc, 5000);   // TURN can be slow to answer; send what we have by then

    const vSender = () => pc.getSenders().find(s => s.track && s.track.kind === "video");
    const send = (m) => { try { if (dc && dc.readyState === "open") dc.send(JSON.stringify(m)); } catch {} };
    const setCam = (on) => { const s = vSender(); if (s) s.track.enabled = on; send({ t: "cam", on }); };
    // every 2 s: link quality; under LOW_BPS three times in a row = picture off (sound keeps going), back on when it recovers
    const timer = setInterval(async () => {
      if (closed || pc.connectionState !== "connected") return;
      let pair = null, bytes = 0;
      try {
        const rep = await pc.getStats();
        rep.forEach(r => { if (r.type === "transport" && r.selectedCandidatePairId) pair = rep.get(r.selectedCandidatePairId); });
        if (!pair) rep.forEach(r => { if (r.type === "candidate-pair" && r.nominated && r.state === "succeeded") pair = r; });
        if (pair) {
          const lc = rep.get(pair.localCandidateId), rc = rep.get(pair.remoteCandidateId);
          net.relay = !!((lc && lc.candidateType === "relay") || (rc && rc.candidateType === "relay"));
          net.kbps = Math.round((pair.availableOutgoingBitrate || 0) / 1000);
          bytes = (pair.bytesSent || 0) + (pair.bytesReceived || 0);
          net.mb = Math.round(bytes / 1e5) / 10;
        }
      } catch { return; }
      if (pair && pair.availableOutgoingBitrate) {
        lowN = pair.availableOutgoingBitrate < LOW_BPS ? lowN + 1 : 0;
        if (lowN >= 3 && camOn && !autoOff) { autoOff = true; setCam(false); }
        else if (autoOff && pair.availableOutgoingBitrate > LOW_BPS * 2) { autoOff = false; if (camOn) setCam(true); }
      }
      net.low = autoOff;
      o.onNet && o.onNet({ ...net });
    }, 2000);

    return {
      sdp: pc.localDescription.sdp,
      hasVideo: camOn,
      accept: (sdp) => pc.setRemoteDescription({ type: "answer", sdp }),
      send,
      mute: (on) => { for (const t of stream.getAudioTracks()) t.enabled = !on; send({ t: "mic", on: !on }); },
      camera: (on) => { camOn = on; if (!autoOff) setCam(on); },
      net: () => ({ ...net }),
      close: () => {
        if (closed) return; closed = true; clearInterval(timer);
        try { send({ t: "bye" }); } catch {}
        for (const t of stream.getTracks()) t.stop();
        setTimeout(() => { try { pc.close(); } catch {} }, 200);   // let "bye" leave first
      },
    };
  }
  const can = () => !!(window.isSecureContext && window.RTCPeerConnection && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  return { open, can, tune };
})();
