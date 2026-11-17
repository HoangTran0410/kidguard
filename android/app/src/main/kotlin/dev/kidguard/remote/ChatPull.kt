package dev.kidguard

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Family chat, "older messages" for the parent (docs/flows/remote.md 2.15): state carries the last 30 of the 100 kept; the page asks for
 * the rest a page at a time. LAN `GET /chat?before=&n=`, relay command `chatPull {fid, before, n}` + file channel (same shape as
 * `smsPull`). Answer = `ChatLogic.page` JSON `{chat:[...oldest first], more}`. No rule needed: the parent already reads the thread.
 */
object ChatPull {
    private const val TAG = "KidGuard"

    fun api(g: Context, query: Map<String, String>): JSONObject =
        ChatLogic.page(Files.chatView(g, Store.of(g).chat()), query["before"]?.toLongOrNull() ?: 0L, ChatLogic.count(query["n"]?.toIntOrNull() ?: 0))

    /** Relay command: "ok" = started (the page finds the file `fid`), "bad fid". */
    fun command(g: Context, c: JSONObject): String {
        val fid = c.optString("fid")
        if (!FilesLogic.isId(fid)) return "bad fid"
        val body = api(g, mapOf("before" to c.optLong("before", 0).toString(), "n" to c.optInt("n", 0).toString()))
        Thread({
            runCatching { Files.pushBytes(g, fid, "kidguard-chat.json", body.toString().toByteArray()) }
                .onFailure { Log.w(TAG, "chat push", it); Files.pushFailed(g, "kidguard-chat.json") }
        }, "chat-push").start()
        return "ok"
    }
}
