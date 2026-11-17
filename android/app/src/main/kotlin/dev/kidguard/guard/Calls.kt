package dev.kidguard

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Call screening (rule `calls`, docs/protocol.md "Calls"). Android 10+ only: the app must hold the CALL_SCREENING role.
 * Basic mode and Device Owner get it through our own adb (AutoGrant: `cmd role add-role-holder`); otherwise the child
 * is asked once (MainActivity line). Blocked call -> event kind "call". Pure decision: [CallsLogic].
 */
class CallScreen : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val block = runCatching { decide(callDetails) }.onFailure { Log.w("KidGuard", "call screen", it) }.getOrDefault(false)
        val b = CallResponse.Builder()
        if (block) b.setDisallowCall(true).setRejectCall(true).setSkipNotification(true)   // stays in the call log as rejected
        respondToCall(callDetails, b.build())
    }

    private fun decide(d: Call.Details): Boolean {
        if (Build.VERSION.SDK_INT < 29 || d.callDirection != Call.Details.DIRECTION_INCOMING) return false
        val s = Store.of(this)
        if (s.released) return false
        val rule = s.rules.calls
        if (rule.block == "off") return false
        val withheld = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val num = if (withheld) null else d.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        if (num != null && runCatching { PhoneNumberUtils.isEmergencyNumber(num) }.getOrDefault(false)) return false
        val known = Calls.canReadContacts(this)
        val block = CallsLogic.shouldBlock(rule, num, num != null && known && Calls.inContacts(this, num), known)
        if (block) Calls.noteBlocked(this, s, num)
        return block
    }
}

object Calls {
    private const val EVENT_GAP_MS = 10 * 60_000L
    private const val CACHE_MS = 20_000L
    const val ROLE = "android.app.role.CALL_SCREENING"
    private val lastEv = HashMap<String, Long>()
    private var cacheAt = 0L; private var cache = JSONArray()

    fun supported() = Build.VERSION.SDK_INT >= 29 && !Flavor.HOME
    fun wanted(ctx: Context) = supported() && Store.of(ctx).rules.calls.block != "off"
    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun canReadContacts(ctx: Context) = has(ctx, Manifest.permission.READ_CONTACTS)
    fun canReadLog(ctx: Context) = has(ctx, Manifest.permission.READ_CALL_LOG)

    fun roleHeld(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 29 && runCatching {
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(ROLE)
    }.getOrDefault(false)

    /** state `callsCap` */
    fun cap(ctx: Context): JSONObject = JSONObject().put("api", supported()).put("screen", roleHeld(ctx))
        .put("log", canReadLog(ctx)).put("contacts", canReadContacts(ctx))

    /** The intent that asks the child to let KidGuard screen calls (null = not needed / not possible). */
    fun roleIntent(ctx: Context): android.content.Intent? {
        if (!wanted(ctx) || roleHeld(ctx)) return null
        return runCatching { ctx.getSystemService(RoleManager::class.java).takeIf { it.isRoleAvailable(ROLE) }?.createRequestRoleIntent(ROLE) }.getOrNull()
    }

    /** Own-adb commands for what is missing (AutoGrant.missing): the two runtime permissions and the role. */
    fun shellNeeds(ctx: Context): List<String> {
        if (Flavor.HOME) return emptyList()
        val p = ctx.packageName
        return buildList {   // the two permissions always (the page's recent-calls list), the role only while blocking is on
            if (!canReadContacts(ctx)) add("pm grant $p ${Manifest.permission.READ_CONTACTS}")
            if (!canReadLog(ctx)) add("pm grant $p ${Manifest.permission.READ_CALL_LOG}")
            if (wanted(ctx) && !roleHeld(ctx)) add("cmd role add-role-holder --user 0 $ROLE $p")
        }
    }

    fun inContacts(ctx: Context, number: String): Boolean = runCatching {
        val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /** Event for a call we turned away; the same caller again within 10 min is not repeated. */
    fun noteBlocked(ctx: Context, s: Store, number: String?) {
        val key = CallsLogic.canon(number).ifEmpty { "hidden" }; val now = System.currentTimeMillis()
        synchronized(lastEv) { if (now - (lastEv[key] ?: 0L) < EVENT_GAP_MS) return; lastEv[key] = now }
        val who = number?.let { CallsLogic.clean(it) ?: it } ?: ctx.getString(R.string.call_hidden)
        runCatching { s.addEvent(Ev("call", ctx.getString(R.string.ev_call_blocked, who))); s.log(ctx.getString(R.string.ev_call_blocked, who), "calls"); Guard.running?.kick() }
        synchronized(this) { cacheAt = 0 }
    }

    /** state `calls`: the last 30 calls of the last 7 days (READ_CALL_LOG), newest first. Cached 20 s. */
    @Synchronized fun recent(ctx: Context, now: Long = System.currentTimeMillis()): JSONArray {
        if (Flavor.HOME || !canReadLog(ctx)) return JSONArray()
        if (now - cacheAt < CACHE_MS) return cache
        val out = JSONArray()
        runCatching {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} > ?", arrayOf((now - 7 * 24 * 3_600_000L).toString()), "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.length() < 30) {
                    val o = JSONObject().put("n", c.getString(0) ?: "").put("type", CallsLogic.typeName(c.getInt(2))).put("t", c.getLong(3)).put("dur", c.getLong(4))
                    c.getString(1)?.takeIf { it.isNotBlank() }?.let { o.put("name", it.take(40)) }
                    out.put(o)
                }
            }
        }.onFailure { Log.w("KidGuard", "call log", it) }
        cacheAt = now; cache = out
        return out
    }

    /** Release: stop screening. A role we added through adb is taken away again; one the child accepted stays (Settings -> Default apps). */
    fun release(ctx: Context, s: Store) {
        if (Build.VERSION.SDK_INT < 29 || !s.callsRole) return
        runCatching { if (SelfAdb.hasKey(ctx)) SelfAdb.run(ctx, "cmd role remove-role-holder --user 0 $ROLE ${ctx.packageName}") }
            .onFailure { Log.w("KidGuard", "role release", it) }
        s.callsRole = false
    }
}
