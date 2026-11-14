package dev.kidguard

import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Pure parts of [RelayPair] (unit-tested, Android-free). */
object RelayPairLogic {
    private val INVITE = Regex("^[0-9a-f]{16}$")
    const val INVITE_KEY = "kg_invite"          // PROVISIONING_ADMIN_EXTRAS_BUNDLE key (docs/protocol.md "Pair over relay")
    const val INVITE_TTL_MS = 3 * 3600_000L      // same as the worker's INVITE_TTL

    /** The invite id from the provisioning extras value, or null when it is not one. */
    fun invite(v: String?): String? = v?.trim()?.takeIf { INVITE.matches(it) }

    /** The relay URL to pair over: https only. */
    fun usable(url: String) = url.startsWith("https://", ignoreCase = true)

    /** How the phone answers the relay's reply to `GET /p/<key>`. */
    sealed class Poll {
        object Waiting : Poll()
        object Taken : Poll()
        object Gone : Poll()
        data class Req(val pin: String, val newPin: String) : Poll()
    }
    fun parsePoll(status: Int, body: String): Poll {
        if (status == 404 || status == 403) return Poll.Gone
        if (status != 200) return Poll.Waiting
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return Poll.Waiting
        if (o.optBoolean("taken")) return Poll.Taken
        val r = o.optJSONObject("req") ?: return Poll.Waiting
        return Poll.Req(r.optString("pin"), r.optString("newPin"))
    }
}

/**
 * Pair from anywhere, phone side (docs/flows/remote.md 2.1 "1c"). Only a phone with no parent yet (never paired, not the Home flavor):
 * - the pairing code the child screen shows is also offered to the default relay (`POST /p/new`), so a parent anywhere can type it;
 * - a provisioning invite (QR extras, [Store.inviteId]) is claimed on the relay by itself.
 * The relay only carries the parent's request (PIN / new PIN) here: the decision is [Store.tryPair] (same PIN rules and lockouts as the LAN flow).
 * The new relay link is committed when the parent's page has taken it (`taken`), like the LAN flow's lazy commit.
 */
class RelayPair(private val g: Guard) {
    private val store = Store.of(g)
    private val h = Handler(HandlerThread("relay-pair").apply { start() }.looper)
    private val run = Runnable { step() }
    private var secret = ""
    private var offered = ""      // the code the relay holds for this phone
    private var offeredAt = 0L
    private var pendTok = ""      // the token handed to the relay, committed on `taken`
    private var pendId = ""

    fun start() { h.postDelayed(run, 3_000) }
    fun stop() { h.removeCallbacks(run); h.looper.quitSafely() }
    /** The child screen came to the front / an invite arrived: look now. */
    fun nudge() { h.removeCallbacks(run); h.post(run) }

    private fun step() {
        val next = try { tick() } catch (e: Exception) { Log.w("KidGuard", "relay pair: ${e.message}"); 15_000L }
        h.removeCallbacks(run); h.postDelayed(run, next)
    }

    /** @return ms until the next look. */
    private fun tick(): Long {
        val url = Relay.DEFAULT_URL
        if (Flavor.HOME || store.everPaired || !RelayPairLogic.usable(url)) { offered = ""; pendTok = ""; return 60_000 }
        val now = System.currentTimeMillis()
        val inv = store.inviteId()
        if (inv != null) {
            if (secret.isEmpty()) secret = Store.randomHex(16)
            return if (poll(url, inv, true)) 3_000 else 5_000
        }
        val on = g.getSystemService(PowerManager::class.java)?.isInteractive == true
        val code = store.visiblePairCode()
        if (!on || code == null) { offered = ""; return 15_000 }   // the screen shows no code: nothing to offer
        if (code != offered || now - offeredAt > 10 * 60_000L) {
            if (secret.isEmpty() || code != offered) secret = Store.randomHex(16)
            val st = call(url, "POST", "/p/new", JSONObject().put("sh", Store.sha256(secret)).put("code", code), false).first
            if (st == 409) { store.newPairCode(); return 1_000 }   // somebody else holds this code on the relay: use another
            if (st != 200) return 20_000
            offered = code; offeredAt = now
        }
        return if (poll(url, code, false)) 2_000 else 3_000
    }

    /** One `GET /p/<key>`; true = a request was answered or the link was committed (look again soon). */
    private fun poll(url: String, key: String, invite: Boolean): Boolean {
        val (st, body) = call(url, "GET", "/p/$key", null, true)
        when (val p = RelayPairLogic.parsePoll(st, body)) {
            RelayPairLogic.Poll.Waiting -> return false
            RelayPairLogic.Poll.Gone -> { if (invite) store.clearInvite() else offered = ""; return false }
            RelayPairLogic.Poll.Taken -> {
                val ok = pendTok.isNotEmpty() && store.commitRelayPair(pendTok, pendId, url)
                pendTok = ""; offered = ""
                if (invite) store.clearInvite()
                if (ok) { store.log(R.string.log_paired); g.kick() }
                return true
            }
            is RelayPairLogic.Poll.Req -> {
                val t = store.tryPair(offered.takeIf { !invite } ?: "", p.pin.filter { it.isDigit() }, p.newPin.trim(), viaInvite = invite)
                if (t == null) {
                    call(url, "POST", "/p/$key/reply", JSONObject().put("error", store.lastPairError).put("wait", store.pinLockLeft()), true)
                    if (invite) store.clearInvite()
                } else {
                    pendTok = t; pendId = Store.randomHex(8)
                    call(url, "POST", "/p/$key/reply", JSONObject().put("id", pendId).put("token", t), true)
                }
                return true
            }
        }
    }

    private fun call(base: String, method: String, path: String, body: JSONObject?, auth: Boolean): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
            if (auth) c.setRequestProperty("X-KG-Secret", secret)
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val st = c.responseCode
            val text = (if (st in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().take(4096).toByteArray().toString(Charsets.UTF_8) } ?: ""
            return st to text
        } finally { c.disconnect() }
    }
}
