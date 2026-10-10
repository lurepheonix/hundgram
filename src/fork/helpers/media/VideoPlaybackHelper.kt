package desu.inugram.helpers.media

import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.VideoPlayer

/**
 * Playback side of original quality: stock streams a server-transcoded downscale
 * ([TLRPC.MessageMedia.alt_documents] via an adaptive HLS manifest, or
 * [MessageObject.thumbQuality] for inline autoplay) and only uses
 * `media.document` when it happens to be cached. Behind [InuConfig.PLAY_ORIGINAL_VIDEO]
 * the player starts on the original instead; a per-video manual pick still wins.
 */
object VideoPlaybackHelper {
    @JvmStatic
    fun forceOriginal(): Boolean = InuConfig.PLAY_ORIGINAL_VIDEO.value

    @JvmStatic
    fun forceInlineOriginal(): Boolean =
        InuConfig.PLAY_ORIGINAL_VIDEO.value && InuConfig.PLAY_ORIGINAL_VIDEO_INLINE.value

    /**
     * Initial quality for the fullscreen player. Saved per-video picks win;
     * otherwise the original when the master toggle is on, else stock AUTO (null).
     */
    @JvmStatic
    fun inu_initialQuality(
        qualities: ArrayList<VideoPlayer.Quality>?,
        messageObject: MessageObject?,
    ): VideoPlayer.Quality? {
        if (qualities == null || qualities.isEmpty()) return null
        val saved = if (messageObject == null) null else VideoPlayer.getSavedQuality(qualities, messageObject)
        if (saved != null) return saved
        if (!forceOriginal()) return null
        for (q in qualities) {
            if (q.original) return q
        }
        return null
    }

    /**
     * Inline autoplay document override: the original when both toggles are on,
     * else [fallback] (stock pick) untouched.
     */
    @JvmStatic
    fun inu_inlineDocument(messageObject: MessageObject?, fallback: TLRPC.Document?): TLRPC.Document? {
        if (!forceInlineOriginal() || messageObject?.messageOwner?.media?.document == null) return fallback
        return messageObject.messageOwner.media.document
    }
}
