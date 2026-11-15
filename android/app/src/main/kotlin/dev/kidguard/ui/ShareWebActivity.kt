package dev.kidguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Share target ("Chia sẻ" -> KidGuard) for a link: the child asks the parent to open that website (request kind "web").
 * Chrome's blocked page and any app's share sheet work. No UI here: MainActivity shows the ask card.
 */
class ShareWebActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty() + " " + intent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val host = WebLogic.hostInText(text)
        if (host == null) Toasts.show(this, R.string.req_web_none)
        else if (!Store.of(this).everPaired) Toasts.show(this, R.string.req_web_unpaired)
        else runCatching {
            startActivity(Quick.moreIntent(this).putExtra(EXTRA_ASK_WEB, host).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    companion object { const val EXTRA_ASK_WEB = "kg.ask.web" }
}
