package desu.inugram.helpers

import desu.inugram.InuConfig
import org.telegram.messenger.UserConfig

object StoriesHelper {
    // Hide-stories master gate per peer: others' stories are hidden whenever
    // HIDE_STORIES is on; own stories are hidden unless re-enabled via SHOW_OWN_STORIES.
    // SHOW_AVATAR_RINGS overrides both: avatar rings (and their taps) stay
    // visible even when stories are hidden. Archive/strip surfaces keep their
    // own toggles.
    @JvmStatic
    fun hidePeerStories(dialogId: Long, account: Int): Boolean {
        if (!InuConfig.HIDE_STORIES.value) return false
        if (InuConfig.SHOW_AVATAR_RINGS.value) return false
        if (dialogId == UserConfig.getInstance(account).clientUserId) return !InuConfig.SHOW_OWN_STORIES.value
        return true
    }

    // Archive surfaces (folder strip, folder-row ring, avatar tap into hidden
    // stories) stay visible when stories aren't hidden at all, or when explicitly
    // re-enabled for archive via SHOW_STORIES_IN_ARCHIVE.
    @JvmStatic
    fun showArchivedStories(): Boolean =
        !InuConfig.HIDE_STORIES.value || InuConfig.SHOW_STORIES_IN_ARCHIVE.value
}
