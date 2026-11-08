package dev.kidguard

/** "Dọn máy": which preinstalled apps may be hidden / cleared. Pure, Android-free, unit-tested. */
object Debloat {
    const val MAX_HIDDEN = 200

    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    fun validPkg(p: String) = p.length <= 200 && p.matches(PKG)

    /** Never hidden and never cleared. */
    val NEVER_HIDE = setOf(
        "dev.kidguard", "dev.kidguard.parent", "android", "com.android.systemui", "com.android.settings",
        "com.android.phone", "com.android.server.telecom", "com.android.dialer", "com.google.android.dialer",
        "com.samsung.android.dialer", "com.samsung.android.incallui", "com.android.contacts", "com.google.android.contacts",
        "com.android.providers.telephony", "com.android.stk", "com.android.emergency",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.gms.supervision", "com.android.vending",
        "com.google.android.webview", "com.android.webview", "com.google.android.trichromelibrary",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.bluetooth", "com.android.nfc", "com.android.keychain",
        "com.android.inputmethod.latin", "com.google.android.inputmethod.latin", "com.android.certinstaller",
        "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver", "com.android.shell",
        "com.android.location.fused", "com.android.se", "com.android.companiondevicemanager",
        "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
    )
    private val NEVER_PREFIX = listOf("com.android.providers.", "com.android.bluetooth", "com.android.nfc", "com.android.keychain",
        "com.android.cellbroadcast", "com.google.android.ext.", "com.google.android.permissioncontroller",
        "com.android.internal.", "com.android.inputmethod.", "com.android.networkstack", "com.google.android.networkstack",
        "com.android.cts.", "android.")

    /** [extraNever]: dynamic set (Policy's protected apps, launcher, keyboards, self). */
    fun canHide(pkg: String, extraNever: Set<String> = emptySet()): Boolean =
        validPkg(pkg) && pkg !in NEVER_HIDE && pkg !in extraNever && NEVER_PREFIX.none { pkg == it.trimEnd('.') || pkg.startsWith(it) }

    /** Curated "safe to hide" OEM bloat. Never keyboards, launchers, camera, gallery, clock, messaging, phone. */
    val SUGGEST = setOf(
        // Oppo / Realme / OnePlus (ColorOS, HeyTap)
        "com.heytap.market", "com.heytap.browser", "com.heytap.music", "com.heytap.cloud", "com.heytap.themestore",
        "com.heytap.habit.analysis", "com.heytap.pictorial", 
        "com.oppo.market", "com.oppo.music", "com.oppo.browser", "com.oppo.themestore", "com.oppo.community",         "com.coloros.gamespace", "com.coloros.video", "com.coloros.assistantscreen",
        "com.coloros.childrenspace", "com.coloros.soundrecorder", "com.nearme.gamecenter", "com.nearme.instant.platform",
        "com.nearme.statistics.rom", "com.realme.store", "com.realme.link", "com.realmecomm.app", "com.opos.ads", "com.opos.cs",
        "com.oplus.games", "com.oplus.melody", 
        // older ColorOS (5/6, Android 8-9): ad offerwall, theme store, lock-screen magazine, global search, tips, cloud
        "com.coloros.avastofferwall", "com.nearme.themespace", "com.coloros.pictorial", "com.oppo.quicksearchbox",
        "com.coloros.operationtips", "com.oppo.operationManual", "com.oppoex.afterservice", "com.coloros.cloud",
        "com.coloros.accegamesdk", "com.opera.preinstall",
        
        // Xiaomi / MIUI / Redmi / POCO
        "com.miui.msa.global", "com.miui.analytics", "com.miui.systemAdSolution", "com.xiaomi.mipicks", "com.xiaomi.discover",
        "com.mi.globalbrowser", "com.android.browser", "com.miui.videoplayer", "com.miui.player", "com.miui.hybrid", "com.miui.hybrid.accessory",
        "com.miui.cloudservice", "com.miui.cloudbackup", "com.miui.bugreport", "com.miui.miservice", "com.miui.yellowpage",
        "com.miui.fm", "com.miui.huanji", "com.miui.android.fashiongallery", "com.xiaomi.glgm", "com.xiaomi.gamecenter.sdk.service",
        "com.xiaomi.payment", "com.xiaomi.scanner", "com.xiaomi.midrop", "com.mi.android.globalFileexplorer", "com.mi.globalminusscreen",
        "com.miui.cleaner", "com.miui.mishare.connectivity", 
        // Samsung
        "com.samsung.android.bixby.agent", "com.samsung.android.bixby.service", "com.samsung.android.bixby.wakeup",
        "com.samsung.android.app.spage", "com.samsung.android.game.gamehome", "com.samsung.android.game.gametools",
        "com.samsung.android.gametuner.thin", "com.samsung.android.arzone", "com.samsung.android.aremoji", "com.samsung.android.ardrawing",
        "com.samsung.android.mateagent", "com.samsung.android.app.tips", "com.samsung.android.app.sharelive",
        "com.samsung.android.visionintelligence", "com.samsung.android.app.watchmanagerstub", "com.samsung.android.stickercenter",
        "com.samsung.android.themestore", "com.samsung.android.app.social", "com.samsung.android.voc", "com.samsung.android.kidsinstaller",
        "com.sec.android.app.sbrowser", "com.sec.android.app.samsungapps", "com.sec.android.easyMover",
        "com.samsung.android.samsungpass", 
        "com.samsung.android.scloud", 
        "com.samsung.android.tvplus", "com.samsung.android.dynamiclock", "com.samsung.android.livestickers", 
        // Vivo / iQOO
        "com.vivo.browser", "com.vivo.appstore", "com.vivo.easyshare", "com.vivo.space", "com.vivo.game", "com.vivo.gamecenter",
        "com.vivo.weather", "com.vivo.globalsearch", "com.bbk.appstore", "com.bbk.theme", "com.vivo.hiboard",
        "com.vivo.email", "com.vivo.agent", "com.vivo.childrenmode",
        // Huawei / Honor
        "com.huawei.appmarket", "com.huawei.browser", "com.huawei.music", "com.huawei.hwvplayer", "com.huawei.himovie", 
        "com.huawei.tips", "com.huawei.gamebox", "com.huawei.fastapp", "com.huawei.hiviewtunnel",
        "com.huawei.android.thememanager", "com.huawei.vassistant", "com.huawei.search", "com.huawei.mirror", "com.hihonor.appmarket",
        "com.hihonor.browser", 
        // Preinstalled social / streaming stubs
        "com.facebook.katana", "com.facebook.appmanager", "com.facebook.services", "com.facebook.system", "com.facebook.orca",
        "com.netflix.partner.activation", "com.netflix.mediaclient", "com.linkedin.android", "com.booking", "com.amazon.mShop.android.shopping",
        "com.amazon.appmanager", "com.amazon.kindle", "com.spotify.music", "com.instagram.android", "com.zhiliaoapp.musically",
        "com.twitter.android", "com.microsoft.office.officehubrow", "com.microsoft.skydrive", "com.microsoft.appmanager",
        "com.tripadvisor.tripadvisor", "com.ebay.mobile", "com.agoda.mobile.consumer", 
        "com.google.android.videos", "com.google.android.music", "com.google.android.apps.magazines", 
        
    )

    fun isSuggest(pkg: String) = pkg in SUGGEST

    /** Listed in state.preinstalled: system apps with an icon or on the suggest list, plus anything we hid. Never user-installed apps (unless hidden by us). */
    fun listed(system: Boolean, hasIcon: Boolean, pkg: String, hiddenByUs: Boolean): Boolean =
        hiddenByUs || (system && (hasIcon || isSuggest(pkg)))

    /** `suggest` flag of a row: on the curated list AND a system app (a user's own TikTok is never suggested). */
    fun suggested(system: Boolean, pkg: String): Boolean = system && isSuggest(pkg)

    data class Diff(val hide: Set<String>, val show: Set<String>)

    /** [want]: rules.hidden. [had]: what we hid. Hide what is wanted and allowed and not yet ours; show what we hid and is no longer wanted (or no longer allowed). */
    fun diff(want: Set<String>, had: Set<String>, extraNever: Set<String> = emptySet()): Diff {
        val ok = want.filter { canHide(it, extraNever) }.toSet()
        return Diff(ok - had, had - ok)
    }

    /** Suggested first, then label (case-insensitive), then package. */
    fun <T> sorted(items: List<T>, suggest: (T) -> Boolean, label: (T) -> String, pkg: (T) -> String): List<T> =
        items.sortedWith(compareByDescending<T> { suggest(it) }.thenBy { label(it).lowercase() }.thenBy { pkg(it) })

    /** Same pure sort over rows (pkg, label, suggest). */
    data class Row(val pkg: String, val label: String, val suggest: Boolean)
    fun sortRows(rows: List<Row>): List<Row> = sorted(rows, { it.suggest }, { it.label }, { it.pkg })

    /**
     * Packages the OS refused to hide (setApplicationHidden returned false) and the parent still wants hidden.
     * [prev]: refused so far. [failed]/[hid]: this pass's hide failures / successes. [shown]: un-hidden this pass. [want]: rules.hidden.
     * A success, an unhide or the parent dropping the package clears the flag.
     */
    fun refusedAfter(prev: Set<String>, failed: Set<String>, hid: Set<String>, shown: Set<String>, want: Set<String>): Set<String> =
        ((prev + failed) - hid - shown).intersect(want)

    /** Packages release must un-hide: everything we hid plus anything still hidden that the rules listed. */
    fun toUnhideOnRelease(hiddenByUs: Set<String>, rulesHidden: Set<String>, currentlyHidden: Set<String>): Set<String> =
        hiddenByUs + rulesHidden.intersect(currentlyHidden)

    /** clearData is refused for the never-hide set and KidGuard itself. */
    fun canClear(pkg: String, self: String, extraNever: Set<String> = emptySet()): Boolean =
        pkg != self && canHide(pkg, extraNever)

    // ---- no Device Owner: the same through the phone's own adb shell (setup/SelfAdb.kt) ----

    /** Packages handled per pass through the shell (each command is a round trip); the loop continues on the next tick. */
    const val SHELL_BATCH = 20

    /** Reversible "hide" through the shell: disabled for user 0 (the data stays). Null for an invalid package name (never build a command from it). */
    fun disableCmd(pkg: String): String? = if (validPkg(pkg)) "pm disable-user --user 0 $pkg" else null
    fun enableCmd(pkg: String): String? = if (validPkg(pkg)) "pm enable --user 0 $pkg" else null
    fun clearCmd(pkg: String): String? = if (validPkg(pkg)) "pm clear --user 0 $pkg" else null

    /** `pm disable-user` prints "Package x new state: disabled-user"; `pm enable` prints "... new state: enabled". Anything else is a failure. */
    fun disableOk(out: String) = out.contains("new state: disabled-user")
    fun enableOk(out: String) = out.contains("new state: enabled") || out.contains("new state: default")
    fun clearOk(out: String) = out.trim().equals("Success", ignoreCase = true)

    /** Only [SHELL_BATCH] packages at a time, [show] goes first. */
    fun shellBatch(df: Diff): Diff {
        val show = df.show.sorted().take(SHELL_BATCH)
        return Diff(df.hide.sorted().take(SHELL_BATCH - show.size).toSet(), show.toSet())
    }

    /** Shell release: what we disabled and is still disabled. [stillDisabled] is asked from the OS (false = already enabled / uninstalled). */
    fun toEnableOnRelease(disabledByUs: Set<String>, stillDisabled: (String) -> Boolean): Set<String> =
        disabledByUs.filter { validPkg(it) && stillDisabled(it) }.toSet()
}
