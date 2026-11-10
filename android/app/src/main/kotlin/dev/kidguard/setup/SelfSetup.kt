package dev.kidguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/** adb key + certificate kept in app storage; the phone is "paired" with itself under this key. */
class SelfAdbManager(ctx: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val kf = File(ctx.filesDir, "adb_key.pk8"); val cf = File(ctx.filesDir, "adb_cert.der")
        var k: PrivateKey? = null; var c: Certificate? = null
        if (kf.exists() && cf.exists()) runCatching {
            k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            c = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        }
        if (k == null || c == null) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom.getInstance("SHA1PRNG")) }.generateKeyPair()
            val alg = "SHA512withRSA"
            val name = X500Name("CN=KidGuard")
            val from = Date(); val to = Date(System.currentTimeMillis() + 365L * 86400_000)
            val ext = CertificateExtensions().apply { set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(kp.public).identifier)) }
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(alg)))
                set("subject", CertificateSubjectName(name))
                set("key", CertificateX509Key(kp.public))
                set("validity", CertificateValidity(from, to))
                set("issuer", CertificateIssuerName(name))
                set("extensions", ext)
            }
            val impl = X509CertImpl(info).apply { sign(kp.private, alg) }
            k = kp.private; c = impl
            kf.writeBytes(kp.private.encoded); cf.writeBytes(impl.encoded)
        }
        key = k!!; cert = c!!
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "KidGuard"
}

/** Finds the local adbd's pairing / connect port through mDNS. [port] is 0 until seen. */
class AdbPortFinder(ctx: Context, private val type: String) {
    private val nsd = ctx.applicationContext.getSystemService(NsdManager::class.java)
    @Volatile var port = 0
    private var service: String? = null
    private var active = false
    private val ui = Handler(Looper.getMainLooper())

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(t: String?, e: Int) { Log.w(TAG, "$type start failed $e") }
        override fun onStopDiscoveryFailed(t: String?, e: Int) {}
        override fun onDiscoveryStarted(t: String?) {}
        override fun onDiscoveryStopped(t: String?) {}
        override fun onServiceFound(s: NsdServiceInfo) { resolve(s, 0) }
        override fun onServiceLost(s: NsdServiceInfo) { if (s.serviceName == service) { port = 0; service = null } }
    }

    @Suppress("DEPRECATION")
    private fun resolve(s: NsdServiceInfo, tries: Int) {
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { if (active && tries < 5) ui.postDelayed({ resolve(s, tries + 1) }, 400) }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    // other phones on the Wi-Fi advertise too: only take the one on this device
                    if (isLocal(i.host)) { port = i.port; service = i.serviceName; Log.i(TAG, "$type -> ${i.host}:${i.port}") }
                }
            })
        }
    }

    fun start() { if (active) return; active = true; runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) } }
    fun stop() { if (!active) return; active = false; port = 0; runCatching { nsd.stopServiceDiscovery(listener) } }

    private fun isLocal(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress) return true
        return runCatching { NetworkInterface.getNetworkInterfaces().toList().any { n -> n.inetAddresses.toList().any { it.hostAddress == a.hostAddress } } }.getOrDefault(false)
    }

    companion object { const val TAG = "KidGuardSelf"; const val PAIRING = "_adb-tls-pairing._tcp"; const val CONNECT = "_adb-tls-connect._tcp" }
}

/** Pure helpers (unit tested). */
object SelfSetupLogic {
    /** ColorOS (Oppo/Realme/OnePlus) refuses adb permission grants until "Disable permission monitoring" is on. */
    fun permMonitoring(out: String) = Regex("UPDATE_APP_OPS_STATS|GRANT_RUNTIME_PERMISSIONS|WRITE_SECURE_SETTINGS").containsMatchIn(out)

    private val ACCOUNT = Regex("""Account \{name=([^,}]*), type=([^}]*)\}""")

    /** Accounts listed by `dumpsys account` as "name (type)". */
    fun accounts(dump: String): List<String> = ACCOUNT.findAll(dump).map { "${it.groupValues[1]} (${it.groupValues[2]})" }.distinct().toList()

    /** New value for enabled_accessibility_services, or null if our entry is already there. */
    fun withAccessibility(current: String, entry: String): String? {
        val cur = current.trim().let { if (it == "null") "" else it }
        if (cur.split(':').contains(entry)) return null
        return if (cur.isEmpty()) entry else "$cur:$entry"
    }

    fun ok(out: String) = !Regex("Error|Exception|denied|Failure", RegexOption.IGNORE_CASE).containsMatchIn(out)
}

/**
 * The self-setup job. Lives in the process (not the activity) so it keeps going while the user is in Settings and the
 * code arrives through a notification reply.
 */
object SelfSetup {
    const val PKG = "dev.kidguard"
    const val ACC = "$PKG/$PKG.Acc"
    const val STEP_TODO = 0; const val STEP_RUN = 1; const val STEP_OK = 2; const val STEP_ERR = 3
    /** Step labels (string resources), in order. */
    val STEPS = intArrayOf(
        R.string.step_pair, R.string.step_accounts, R.string.step_owner,
        R.string.step_usage, R.string.step_secure, R.string.step_acc, R.string.step_battery,
    )
    private const val CH = "selfsetup"; private const val NID = 41; const val KEY_CODE = "code"; const val ACTION_CODE = "dev.kidguard.PAIR_CODE"

    val state = IntArray(STEPS.size)
    @Volatile var message = ""          // last error / final line
    @Volatile var running = false
    @Volatile var done = false
    @Volatile var turnOffWifi = true
    @Volatile var wifiOff: Boolean? = null  // result of the optional last step
    @Volatile var pairOnly = false      // set by SelfAdb.pair: pair + connect, none of the Device Owner steps
    private var pairing: AdbPortFinder? = null
    private var connecting: AdbPortFinder? = null

    val pairingPort get() = pairing?.port ?: 0
    val connectPort get() = connecting?.port ?: 0

    fun startDiscovery(ctx: Context) {
        if (pairing == null) pairing = AdbPortFinder(ctx, AdbPortFinder.PAIRING)
        if (connecting == null) connecting = AdbPortFinder(ctx, AdbPortFinder.CONNECT)
        pairing!!.start(); connecting!!.start()
    }

    fun stopDiscovery() { pairing?.stop(); connecting?.stop() }

    /** Starts the job on a background thread: pair with [code], connect, run every step. Port 0 = use the discovered one. */
    fun start(ctx: Context, code: String, pairPort: Int = 0, connPort: Int = 0, pairOnly: Boolean = false) {
        val app = ctx.applicationContext
        synchronized(this) { if (running) return; running = true; done = false; message = ""; wifiOff = null; state.fill(STEP_TODO) }
        startDiscovery(app)
        Thread({ runCatching { job(app, code.trim(), pairPort, connPort, pairOnly) }.onFailure { Log.w(AdbPortFinder.TAG, "job", it); fail(app, it.message ?: it.toString()) }; this.pairOnly = false; running = false }, "selfsetup").start()
    }

    private fun mark(app: Context, i: Int, s: Int) { state[i] = s; notifyProgress(app, if (s == STEP_RUN) app.getString(STEPS[i]) + "..." else null) }

    private fun fail(app: Context, msg: String) {
        Log.w(AdbPortFinder.TAG, "failed: $msg")
        val i = state.indexOf(STEP_RUN); if (i >= 0) state[i] = STEP_ERR
        message = msg; notifyFinal(app, app.getString(R.string.ss_failed, msg))
    }

    private fun waitFor(ms: Long, get: () -> Int): Int { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { val v = get(); if (v > 0) return v; Thread.sleep(200) }; return 0 }

    private fun job(app: Context, code: String, pairPort: Int, connPort: Int, onlyPair: Boolean) {
        val m = SelfAdb.manager(app)
        // 1) pair, then connect
        mark(app, 0, STEP_RUN)
        val pp = if (pairPort > 0) pairPort else waitFor(8000) { pairingPort }
        if (pp == 0) return fail(app, app.getString(R.string.ss_no_pair_port))
        val paired = runCatching { m.pair("127.0.0.1", pp, code) }
        if (paired.isFailure || paired.getOrNull() != true) return fail(app, app.getString(R.string.ss_pair_fail) + " " + (paired.exceptionOrNull()?.message ?: ""))
        val cp = if (connPort > 0) connPort else waitFor(12000) { connectPort }
        if (cp == 0) return fail(app, app.getString(R.string.ss_no_conn_port))
        Log.i(AdbPortFinder.TAG, "paired, connecting to $cp")
        val c = runCatching { m.connect("127.0.0.1", cp) }
        Log.i(AdbPortFinder.TAG, "connect -> $c")
        if (c.isFailure || c.getOrNull() != true) return fail(app, app.getString(R.string.ss_conn_fail, c.exceptionOrNull()?.message ?: ""))
        state[0] = STEP_OK
        if (onlyPair) {   // SelfAdb.pair: the key is paired, the shell works while Wireless debugging stays on
            runCatching { m.disconnect() }
            SelfAdb.invalidate(); stopDiscovery()
            done = true; message = app.getString(R.string.ss_paired)
            notifyFinal(app, message); Guard.start(app); return
        }

        fun sh(cmd: String): String {
            Log.i(AdbPortFinder.TAG, "sh: $cmd")
            val s = m.openStream("shell:$cmd")
            Log.i(AdbPortFinder.TAG, "stream open")
            // the library throws "Stream closed" at the end of the output instead of returning -1
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread { Thread.sleep(25000); if (!finished.get()) runCatching { s.close() } }.apply { isDaemon = true }.start()   // never hang forever
            val input = s.openInputStream(); val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            try { while (true) { val n = input.read(buf); if (n < 0) break; bo.write(buf, 0, n) } } catch (_: java.io.IOException) { }
            finished.set(true); runCatching { s.close() }
            return bo.toString("UTF-8").trim()
        }
        fun step(i: Int, cmd: String, need: ((String) -> Boolean)? = null): Boolean {
            mark(app, i, STEP_RUN)
            val out = sh(cmd); Log.i(AdbPortFinder.TAG, "$cmd -> $out")
            val good = if (need != null) need(out) else SelfSetupLogic.ok(out)
            if (!good) { state[i] = STEP_ERR; message = out.ifBlank { app.getString(R.string.ss_cmd_fail) }
                if (SelfSetupLogic.permMonitoring(out)) message += "\n" + app.getString(R.string.ss_perm_mon)
                notifyFinal(app, app.getString(R.string.ss_failed, message)); return false }
            state[i] = STEP_OK; return true
        }

        // 2) accounts
        mark(app, 1, STEP_RUN)
        val acc = SelfSetupLogic.accounts(sh("dumpsys account"))
        if (acc.isNotEmpty()) {
            state[1] = STEP_ERR
            message = app.getString(R.string.ss_accounts, acc.joinToString(", "))
            notifyFinal(app, message); runCatching { m.disconnect() }; return
        }
        state[1] = STEP_OK
        // 3..7 (same as setup.sh, minus install)
        // a rerun after a partly failed setup: we already are Device Owner ("already set" would stop it here), so go on
        if (Policy.isOwner(app)) { Log.i(AdbPortFinder.TAG, "already Device Owner: skip set-device-owner"); state[2] = STEP_OK }
        else if (!step(2, "dpm set-device-owner $PKG/.Admin") { it.contains("Success") }) { runCatching { m.disconnect() }; return }
        if (!step(3, "appops set $PKG GET_USAGE_STATS allow")) return
        if (!step(4, "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS")) return
        // optional (remote phone settings): brightness / screen timeout and Do Not Disturb; old Android may not know them
        runCatching { sh("appops set $PKG WRITE_SETTINGS allow") }; runCatching { sh("cmd notification allow_dnd $PKG") }
        runCatching { sh("cmd notification allow_listener $PKG/$PKG.NotifDots") }   // launcher notification dots
        runCatching { sh("appops set $PKG SYSTEM_ALERT_WINDOW allow") }   // bank app reopen (basic mode)
        mark(app, 5, STEP_RUN)
        val merged = SelfSetupLogic.withAccessibility(sh("settings get secure enabled_accessibility_services"), ACC)
        if (merged != null) sh("settings put secure enabled_accessibility_services '$merged'")
        sh("settings put secure accessibility_enabled 1")
        state[5] = STEP_OK
        if (!step(6, "dumpsys deviceidle whitelist +$PKG") { !it.contains("Unknown", true) && !it.contains("Exception", true) }) return
        // ColorOS: a Recents swipe is a real force-stop (alarms gone, Accessibility turned off): lock-card hint (read while the link is up)
        val colorOs = runCatching { sh("getprop ro.build.version.opporom; getprop ro.build.version.oplusrom") }.getOrDefault("").isNotBlank()
        // optional: Wireless debugging off. The link drops with it, so no reading afterwards.
        if (turnOffWifi) { runCatching { sh("settings put global adb_wifi_enabled 0") }; wifiOff = true }
        runCatching { m.disconnect() }
        done = true; message = app.getString(R.string.ss_done)
        if (colorOs) message += "\n" + app.getString(R.string.ss_coloros)
        stopDiscovery()
        notifyFinal(app, message)
        Guard.start(app)
    }

    // ---- notification with a reply box: the pairing dialog closes if the user leaves Settings, so the code is typed here ----
    private fun nm(ctx: Context): NotificationManager {
        val n = ctx.getSystemService(NotificationManager::class.java)
        n.createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.ch_selfsetup), NotificationManager.IMPORTANCE_HIGH))
        return n
    }

    private fun base(ctx: Context) = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_key).setOnlyAlertOnce(true)

    /** Shows "Nhập mã ghép" with an inline reply field. */
    fun askCodeNotification(ctx: Context) {
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, PairCodeReceiver::class.java).setAction(ACTION_CODE).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ri = RemoteInput.Builder(KEY_CODE).setLabel(ctx.getString(R.string.ss_code_label)).build()
        val act = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_key), ctx.getString(R.string.ss_code_action), pi).addRemoteInput(ri).build()
        nm(ctx).notify(NID, base(ctx).setContentTitle(ctx.getString(R.string.ss_code_title))
            .setContentText(ctx.getString(R.string.ss_code_text))
            .setAutoCancel(false).setOngoing(true).addAction(act).build())
    }

    private fun notifyProgress(ctx: Context, text: String?) {
        if (text == null) return
        runCatching { nm(ctx).notify(NID, base(ctx).setContentTitle(ctx.getString(R.string.ss_running)).setContentText(text).setOngoing(true).build()) }
    }

    private fun notifyFinal(ctx: Context, text: String) {
        runCatching { nm(ctx).notify(NID, base(ctx).setContentTitle("KidGuard").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build()) }
    }

    fun cancelNotification(ctx: Context) { runCatching { nm(ctx).cancel(NID) } }
}

/** Receives the 6-digit code typed in the notification and starts the job. */
class PairCodeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SelfSetup.KEY_CODE)?.toString()?.filter { it.isDigit() } ?: return
        if (code.length != 6) {
            SelfSetup.message = ctx.applicationContext.getString(R.string.ss_code_6); SelfSetup.askCodeNotification(ctx.applicationContext); return
        }
        SelfSetup.start(ctx, code, pairOnly = SelfSetup.pairOnly)
    }
}
