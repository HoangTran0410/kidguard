// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
appLang();
applyLang();
boot();
uiPackBoot();   // after boot(): if boot() throws (a broken page) this never runs and the phone rolls the pack back
