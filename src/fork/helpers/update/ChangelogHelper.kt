package desu.inugram.helpers.update

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_iv

// release-note history for the in-settings changelog browser: discovers #release posts
// in the updates channel, keeping only the installed build and older ones. newer channel
// posts (an old build looking at a newer channel) are excluded by construction.
object ChangelogHelper {
    data class Entry(val verCode: Int, val appVerName: String, val messageId: Int)

    sealed interface HistoryResult {
        data class Loaded(val entries: List<Entry>) : HistoryResult
        data class Error(val message: String) : HistoryResult
    }

    private const val PAGE_LIMIT = 50
    private const val MAX_PAGES = 10

    fun loadHistory(callback: (HistoryResult) -> Unit) {
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback(HistoryResult.Error("Not logged in"))
            return
        }
        MessagesController.getInstance(account).userNameResolver.resolve(UpdateHelper.USERNAME) { id ->
            if (id == null || id == 0L || id == Long.MAX_VALUE) {
                callback(HistoryResult.Error("resolve failed"))
                return@resolve
            }
            fetchPage(
                account, id,
                UpdateHelper.currentVariant(), UpdateHelper.installedVersionCode(),
                offsetId = 0, acc = LinkedHashMap(), pagesLeft = MAX_PAGES, callback,
            )
        }
    }

    private fun fetchPage(
        account: Int,
        peerId: Long,
        variant: String,
        current: Int,
        offsetId: Int,
        acc: LinkedHashMap<Int, Entry>,
        pagesLeft: Int,
        callback: (HistoryResult) -> Unit,
    ) {
        val req = TLRPC.TL_messages_search().apply {
            peer = MessagesController.getInstance(account).getInputPeer(peerId)
            q = "#release"
            filter = TLRPC.TL_inputMessagesFilterEmpty()
            limit = PAGE_LIMIT
            offset_id = offsetId
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
            AndroidUtilities.runOnUIThread {
                if (err != null || resp !is TLRPC.messages_Messages) {
                    callback(HistoryResult.Error(err?.text ?: "no response"))
                    return@runOnUIThread
                }
                // truncated stubs may lack the apk documents needed to map a post to a
                // version; resolve those to full messages before parsing.
                val stubs = resp.messages.filter { it.rich_message != null && !acc.containsKey(it.id) }
                val complete = stubs.filter { it.rich_message?.part != true }
                val partial = stubs.filter { it.rich_message?.part == true }
                for (msg in complete) {
                    addIfRelease(msg, variant, current, acc)
                }
                if (partial.isEmpty()) {
                    onPageDone(account, peerId, variant, current, resp, offsetId, acc, pagesLeft, callback)
                } else {
                    var left = partial.size
                    for (msg in partial) {
                        UpdateHelper.loadRichMessage(account, msg.id) { full ->
                            if (full != null) addIfRelease(full, variant, current, acc)
                            if (--left == 0) {
                                onPageDone(account, peerId, variant, current, resp, offsetId, acc, pagesLeft, callback)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onPageDone(
        account: Int,
        peerId: Long,
        variant: String,
        current: Int,
        resp: TLRPC.messages_Messages,
        offsetId: Int,
        acc: LinkedHashMap<Int, Entry>,
        pagesLeft: Int,
        callback: (HistoryResult) -> Unit,
    ) {
        val minId = resp.messages.minOfOrNull { it.id } ?: 0
        if (resp.messages.size >= PAGE_LIMIT && pagesLeft > 1 && minId > 0 && (offsetId == 0 || minId < offsetId)) {
            fetchPage(account, peerId, variant, current, minId, acc, pagesLeft - 1, callback)
            return
        }
        // one entry per build; a reposted release keeps the newest post. navigation walks
        // this list, so builds without any post are skipped naturally.
        val entries = acc.values
            .groupBy { it.verCode }
            .map { (_, group) -> group.maxBy { it.messageId } }
            .sortedByDescending { it.verCode }
        callback(HistoryResult.Loaded(entries))
    }

    private fun addIfRelease(msg: TLRPC.Message, variant: String, current: Int, acc: LinkedHashMap<Int, Entry>) {
        val info = UpdateHelper.parseApkInfo(msg, variant) ?: return
        if (info.verCode > current) return
        acc[msg.id] = Entry(info.verCode, info.appVerName, msg.id)
    }

    // full changelog body of a history entry, or null when the post is gone/unreadable —
    // the caller shows the empty state for those, same as for builds without any post.
    fun loadChangelog(account: Int, entry: Entry, callback: (TL_iv.RichMessage?) -> Unit) {
        UpdateHelper.loadRichMessage(account, entry.messageId) { msg ->
            callback(msg?.let { UpdateHelper.changelogForVariant(it, UpdateHelper.currentVariant()) })
        }
    }
}
