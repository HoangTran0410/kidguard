package dev.kidguard

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** Relay `snapList {fid, before, n}`: one page of the photo history on the file channel (`kidguard-snaps.json`), same shape as `chatPull`. LAN: `GET /snaps`. */
object SnapPull {
    fun command(g: Context, c: JSONObject): String {
        val fid = c.optString("fid")
        if (!FilesLogic.isId(fid)) return "bad fid"
        if (!RemoteSnap.on(g)) return "off"
        val body = RemoteSnap.api(g, mapOf("before" to c.optLong("before", 0).toString(), "n" to c.optInt("n", 0).toString()))
        Thread({
            runCatching { Files.pushBytes(g, fid, "kidguard-snaps.json", body.toString().toByteArray()) }
                .onFailure { Log.w("KidGuard", "snap list push", it); Files.pushFailed(g, "kidguard-snaps.json") }
        }, "snap-list-push").start()
        return "ok"
    }
}

/** Bulk delete of chat messages (`chatDel {ids:[message ids], files?: true}`): the child's thread loses them; `files` also deletes their attachments. */
object ChatDel {
    fun command(g: Context, c: JSONObject): String {
        val ids = FilesLogic.ids(c) ?: return "bad id"
        val st = Store.of(g)
        if (c.optBoolean("files")) ChatLogic.attIdsOf(st.chat(), ids.toSet()).forEach { Files.delete(g, it) }
        val n = st.chatRemove(ids.toSet())
        if (n > 0) st.log(R.string.log_chat_del, n)
        Guard.running?.kick()
        return "ok"
    }
}
