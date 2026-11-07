package dev.kidguard.parent

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language ([Langs] codes, "" = follow the phone).
 * Android 13+: the system per-app language (LocaleManager), same setting as Settings > Apps > KidGuard Phụ huynh > Language;
 * the system re-applies it to every component and recreates activities itself.
 * Android 8-12: the choice lives in prefs; activities wrap their base with [wrap] (recreate after a change), the
 * Application and services return [res] from getResources(), so notifications / events / push / check-job notifications follow it too.
 */
object Lang {
    private const val PREFS = "lang"
    @Volatile private var code: String? = null
    private class Cached(val code: String, val base: Configuration, val res: Resources)
    @Volatile private var cache: Cached? = null

    private fun stored(c: Context): String = code ?: runCatching {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("code", "") ?: ""
    }.getOrDefault("").let { if (Langs.valid(it)) it else "" }.also { code = it }

    fun get(c: Context): String =
        if (Build.VERSION.SDK_INT >= 33) Langs.norm(c.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags())
        else stored(c)

    /** Sets the language; false (nothing done) when [newCode] is unknown or already the current one. */
    fun set(c: Context, newCode: String): Boolean {
        if (!Langs.valid(newCode) || get(c) == newCode) return false
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = c.getSystemService(LocaleManager::class.java) ?: return false
            lm.applicationLocales = if (newCode.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(newCode)
        } else {
            c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("code", newCode).commit()
            code = newCode; cache = null
        }
        return true
    }

    /** Android 8-12 with a language chosen: [base]'s resources in that language. Null = use the normal ones. */
    fun res(base: Context?): Resources? {
        if (base == null || Build.VERSION.SDK_INT >= 33) return null
        val c = stored(base)
        if (c.isEmpty()) return null
        val bc = base.resources.configuration
        cache?.let { if (it.code == c && it.base == bc) return it.res }
        val r = base.createConfigurationContext(Configuration(bc).apply { setLocale(Locale.forLanguageTag(c)) }).resources
        cache = Cached(c, Configuration(bc), r)
        return r
    }

    /** For Activity.attachBaseContext: [base] in the chosen language (Android 8-12), else [base] as it is. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val c = stored(base)
        if (c.isEmpty()) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(c)) })
    }
}

/** Application: applicationContext (CheckJob, Push, ResTr) answers in the chosen language. */
class App : Application() {
    override fun getResources(): Resources = Lang.res(baseContext) ?: super.getResources()
}
