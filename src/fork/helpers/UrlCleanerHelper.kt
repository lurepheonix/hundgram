package desu.inugram.helpers

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.SuggestionSpan
import android.text.style.URLSpan
import android.util.Log
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.core.net.toUri
import desu.inugram.InuConfig
import desu.inugram.core.urlcleaner.UrlCleaner
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.ui.Components.URLSpanReplacement
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UrlCleanerHelper {
    private const val FILE = "adguard_url_tracking.txt"
    private const val FILTER_URL = "https://filters.adtidy.org/android/filters/17.txt"
    private val TIME_UPDATED_RE = Regex("""^!\s*TimeUpdated:\s*(\S+)""", RegexOption.MULTILINE)

    @Volatile
    private var cleaner: UrlCleaner? = null

    @Volatile
    private var loaded = false

    /** Date prefix of `! TimeUpdated:` from the active filter source, or null. */
    @Volatile
    var lastUpdated: String? = null
        private set

    val isUsingOverride: Boolean get() = overrideFile().exists()

    private fun overrideFile() = File(ApplicationLoader.applicationContext.filesDir, FILE)

    private fun readSource(): String =
        overrideFile().takeIf { it.exists() }?.readText()
            ?: ApplicationLoader.applicationContext.assets.open(FILE).bufferedReader().use { it.readText() }

    private fun parseTimeUpdated(text: String): String? =
        TIME_UPDATED_RE.find(text)?.groupValues?.get(1)?.substringBefore('T')

    @Synchronized
    private fun getCleaner(): UrlCleaner? {
        if (loaded) return cleaner
        cleaner = try {
            val text = readSource()
            lastUpdated = parseTimeUpdated(text)
            UrlCleaner.fromAdGuardFilter(text)
        } catch (e: Throwable) {
            Log.e("UrlCleanerHelper", "failed to load filter", e)
            null
        }
        loaded = true
        return cleaner
    }

    @Synchronized
    fun invalidate() {
        cleaner = null
        loaded = false
        lastUpdated = null
    }

    fun preload() {
        getCleaner()
    }

    /**
     * Download latest filter. Blocks — call from a background queue.
     * Returns true if the downloaded copy was written; false if it matches the active source.
     */
    @Throws(java.io.IOException::class)
    fun fetchLatest(): Boolean {
        getCleaner()
        val currentTime = lastUpdated

        val conn = (URL(FILTER_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val downloaded = try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        if (parseTimeUpdated(downloaded)?.let { it == currentTime } == true) return false

        val target = overrideFile()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(downloaded)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("rename failed")
        }
        invalidate()
        return true
    }

    fun resetToBundled() {
        overrideFile().delete()
        invalidate()
    }

    @JvmStatic
    fun clean(uri: Uri): Uri {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value) return uri
        val original = uri.toString()
        val cleaned = cleanUrl(original)
        return if (cleaned === original) uri else cleaned.toUri()
    }

    private fun cleanUrl(url: String): String {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return url
        return getCleaner()?.clean(url) ?: url
    }

    @JvmStatic
    fun cleanUrlString(url: CharSequence): CharSequence {
        val stripOn = InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value
        val replaceOn = IdnUrlHelper.replaceEnabled()
        if (!stripOn && !replaceOn) return url
        val s = url.toString()
        var out = if (stripOn) cleanUrl(s) else s
        if (replaceOn) out = IdnUrlHelper.decodeForReplace(out)
        return if (out === s) url else out
    }

    /**
     * Scans URLs in [text]: strips tracking params, then applies IDN conversion
     * (pretty display spans, or plain in-place replacement); returns same ref if nothing changed.
     */
    @JvmStatic
    fun cleanText(text: CharSequence?): CharSequence? {
        if (text.isNullOrEmpty()) return text
        val stripOn = InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value
        val replaceOn = IdnUrlHelper.replaceEnabled()
        val prettyOn = IdnUrlHelper.prettyEnabled() && !replaceOn
        if (!stripOn && !replaceOn && !prettyOn) return text
        val matcher = AndroidUtilities.WEB_URL?.matcher(text) ?: return text
        var out: SpannableStringBuilder? = null
        var shift = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val original = text.subSequence(start, end).toString()
            val stripped = if (stripOn) cleanUrl(original) else original
            if (replaceOn) {
                val decoded = IdnUrlHelper.decodeForReplace(stripped)
                if (decoded === original) continue
                if (out == null) out = SpannableStringBuilder(text)
                out.replace(start + shift, end + shift, decoded)
                shift += decoded.length - (end - start)
            } else if (prettyOn) {
                val display = IdnUrlHelper.decodeForDisplay(stripped)
                if (display === stripped) {
                    if (stripped === original) continue
                    if (out == null) out = SpannableStringBuilder(text)
                    out.replace(start + shift, end + shift, stripped)
                    shift += stripped.length - (end - start)
                } else {
                    if (out == null) out = SpannableStringBuilder(text)
                    val newStart = start + shift
                    out.replace(newStart, end + shift, display)
                    shift += display.length - (end - start)
                    out.setSpan(
                        URLSpanReplacement(stripped),
                        newStart, newStart + display.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else {
                if (stripped === original) continue
                if (out == null) out = SpannableStringBuilder(text)
                out.replace(start + shift, end + shift, stripped)
                shift += stripped.length - (end - start)
            }
        }
        if (out != null && out.length != text.length) {
            BaseInputConnection.removeComposingSpans(out)
            for (span in out.getSpans(0, out.length, SuggestionSpan::class.java)) {
                out.removeSpan(span)
            }
        }
        return out ?: text
    }

    /** Returns true if the paste was consumed (text inserted). */
    @JvmStatic
    fun handleContextMenuPaste(editText: EditText): Boolean {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value && !IdnUrlHelper.prettyEnabled()) return false
        val ctx = editText.context ?: return false
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = cb.primaryClip?.takeIf { it.itemCount >= 1 } ?: return false
        try {
            val raw = clip.getItemAt(0).coerceToText(ctx) ?: return false
            val cleaned = cleanText(raw) ?: return false
            if (cleaned === raw) return false
            val text = editText.text ?: return false
            val start = maxOf(0, editText.selectionStart)
            val end = minOf(text.length, editText.selectionEnd)
            text.replace(start, end, cleaned)
            editText.setSelection(start + cleaned.length)
            return true
        } catch (e: Throwable) {
            FileLog.e(e)
            return false
        }
    }

    /** Intercepts IME commits (Gboard clipboard chips etc.). */
    @JvmStatic
    fun wrapInputConnection(ic: InputConnection?): InputConnection? {
        if (ic == null) return null
        return object : InputConnectionWrapper(ic, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val cleaned = if (text != null &&
                    (InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value || IdnUrlHelper.prettyEnabled()) &&
                    text.toString().contains("http")
                ) cleanText(text) else text
                return super.commitText(cleaned, newCursorPosition)
            }
        }
    }

    @JvmStatic
    fun cleanSpannedUrls(text: SpannableStringBuilder) {
        val stripOn = InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value
        val replaceOn = IdnUrlHelper.replaceEnabled()
        val prettyOn = IdnUrlHelper.prettyEnabled() && !replaceOn
        if (!stripOn && !replaceOn && !prettyOn) return
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val url = span.url ?: continue
            val stripped = if (stripOn) cleanUrl(url) else url
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val flags = text.getSpanFlags(span)
            // display text of a rich-text link stays author-chosen; only bare links
            // (display == href) get their display converted along with the href
            val bare = text.subSequence(start, end).toString() == url
            if (replaceOn) {
                val decoded = IdnUrlHelper.decodeForReplace(stripped)
                if (bare && decoded != text.subSequence(start, end).toString()) {
                    text.removeSpan(span)
                    text.replace(start, end, decoded)
                    val replacement = if (span is URLSpanReplacement) URLSpanReplacement(decoded, span.textStyleRun)
                    else URLSpan(decoded)
                    text.setSpan(replacement, start, start + decoded.length, flags)
                } else if (decoded !== url) {
                    text.removeSpan(span)
                    val replacement = if (span is URLSpanReplacement) URLSpanReplacement(decoded, span.textStyleRun)
                    else URLSpan(decoded)
                    text.setSpan(replacement, start, end, flags)
                }
            } else if (prettyOn && bare) {
                val display = IdnUrlHelper.decodeForDisplay(stripped)
                if (display === stripped && stripped === url) continue
                text.removeSpan(span)
                text.replace(start, end, display)
                val replacement = if (span is URLSpanReplacement) URLSpanReplacement(stripped, span.textStyleRun)
                else URLSpanReplacement(stripped)
                text.setSpan(replacement, start, start + display.length, flags)
            } else {
                if (stripped === url) continue
                text.removeSpan(span)
                val replacement = if (span is URLSpanReplacement) URLSpanReplacement(stripped, span.textStyleRun)
                else URLSpan(stripped)
                text.setSpan(replacement, start, end, flags)
            }
        }
    }
}
