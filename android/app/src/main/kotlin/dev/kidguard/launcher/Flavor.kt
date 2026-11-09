package dev.kidguard

/**
 * Which APK this is. kid = KidGuard (child app + kid launcher). home = "KidGuard Home": the same launcher on its own
 * (own package dev.kidguard.home, src/home/AndroidManifest.xml removes the Guard service, admin, accessibility, relay...).
 * Home leaves out everything that needs a parent or the Guard: SOS / call / ask-for-time, chores, files, warnings,
 * the parent's widgets, the "Mở KidGuard" rows.
 */
object Flavor {
    val HOME: Boolean = BuildConfig.HOME

    /** KidGuard widgets that work without a parent (data on the phone: layout, timetable, clock, battery). */
    private val HOME_WIDGETS = setOf("clock", "analog", "month", "battery", "todo", "note", "class", "timer", "streak")
    fun widget(type: String) = !HOME || type in HOME_WIDGETS || type == HomeLayout.APP

    /** "Hôm nay" cards without a parent. */
    private val HOME_CARDS = setOf("now", "sched", "tomorrow", "notes")
    fun card(id: String) = !HOME || id in HOME_CARDS
}
