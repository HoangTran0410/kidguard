package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The home screen of the launcher's safe mode (3 crashes in 5 min, [LauncherSafe.crashed]): a plain list of the apps,
 * plain platform views, nothing from the kid layout (home.json, widgets, style), so whatever crashed the full home is
 * not on this path. The Guard still blocks whatever the rules block when it opens. "Try again" goes back to the full
 * home at once; after 24 h it does by itself ([HomeActivity] onResume).
 * Owner 2026-11-18: "lỡ có crash thì phải có backup chứ không được để user stuck".
 */
object SafeHome {
    fun view(act: Activity, retry: () -> Unit): View {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val bg = Color.rgb(18, 22, 30); val fg = Color.rgb(236, 239, 244); val sub = Color.rgb(160, 168, 182)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(48), dp(20), dp(32)) }
        col.addView(TextView(act).apply { text = act.getString(R.string.safe_home_title); textSize = 22f; setTextColor(fg) })
        col.addView(TextView(act).apply { text = act.getString(R.string.safe_home_body); textSize = 14f; setTextColor(sub); setPadding(0, dp(6), 0, dp(12)) })
        col.addView(Button(act).apply { text = act.getString(R.string.safe_home_retry); setOnClickListener { retry() } })
        val pm = act.packageManager
        val apps = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it to (runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.activityInfo.packageName)) }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
        for ((ri, label) in apps) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    runCatching {
                        act.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            row.addView(ImageView(act).apply { runCatching { setImageDrawable(ri.loadIcon(pm)) } }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(act).apply { text = label; textSize = 17f; setTextColor(fg); setPadding(dp(16), 0, 0, 0) })
            col.addView(row)
        }
        return ScrollView(act).apply { setBackgroundColor(bg); addView(col) }
    }
}
