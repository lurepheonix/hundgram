package desu.inugram.helpers

import desu.inugram.InuConfig
import desu.inugram.core.urldisplay.IdnUrlDecoder

/**
 * Internationalized URL conversion on paste: brings punycode hosts and
 * percent-escaped paths back to a readable Unicode form, after [UrlCleanerHelper]
 * strips tracking parameters.
 *
 * Two modes, wired to the Links & browser toggles:
 * - pretty display (master): the matched URL keeps its original encoded href and
 *   only its display text becomes the wide-decoded form (all scripts, bidi and
 *   control characters excluded). Safe: the link itself is untouched.
 * - in-place replacement (child, experimental): the URL text itself is rewritten
 *   with the narrow-decoded form (European Latin + Cyrillic only) as plain text.
 */
object IdnUrlHelper {
    /** Master toggle: pretty display, href preserved. */
    @JvmStatic
    fun prettyEnabled(): Boolean = InuConfig.IDN_URL_PRETTY_LINK.value

    /** Child toggle, only meaningful with [prettyEnabled]: rewrite the URL itself. */
    @JvmStatic
    fun replaceEnabled(): Boolean = prettyEnabled() && InuConfig.IDN_URL_REPLACE_IN_PLACE.value

    /** Narrow decode of a single URL for in-place replacement; same ref when disabled/unchanged. */
    @JvmStatic
    fun decodeForReplace(url: String): String =
        if (!replaceEnabled()) url else IdnUrlDecoder.decodeNarrow(url)

    /** Wide decode of a single URL for link display; same ref when disabled/unchanged. */
    @JvmStatic
    fun decodeForDisplay(url: String): String =
        if (!prettyEnabled()) url else IdnUrlDecoder.decodeWide(url)
}
