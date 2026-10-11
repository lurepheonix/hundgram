package desu.inugram.core.urldisplay

import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Selectively decodes internationalized URLs back to a readable Unicode form.
 *
 * Two policies, because the two paste modes have different blast radii:
 * - narrow ([decodeNarrow]): only European Latin, Slavic Latin and Cyrillic
 *   (U+0080–U+024F, U+1E00–U+1EFF, U+0400–U+04FF). Used when the decoded text
 *   *replaces* the URL itself, so anything else (CJK, emoji, RTL, …) stays
 *   encoded rather than rewriting a link the server may only accept as ASCII.
 * - wide ([decodeWide]): every script, including CJK. Used when the decoded
 *   text is only the *display* of a link whose href keeps the original encoded
 *   URL, so decoding cannot break the request. Still refuses bidi overrides,
 *   controls, whitespace and malformed sequences rather than rendering them.
 *
 * Both policies never decode percent-runs that resolve to ASCII (so `%2F`,
 * `%41`, … stay encoded and URL structure is preserved) and pass malformed
 * input through untouched. Returns the input reference when nothing changed.
 */
object IdnUrlDecoder {

    fun decodeNarrow(url: String): String = decode(url, wide = false)

    fun decodeWide(url: String): String = decode(url, wide = true)

    fun decode(url: String, wide: Boolean): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return url

        var changed = false
        val rest = url.substring(schemeEnd + 3)
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.takeIf { it >= 0 } ?: rest.length
        val auth = rest.substring(0, authEnd)
        val after = rest.substring(authEnd)
        val decodedAuth = decodeAuthority(auth, wide)
        if (decodedAuth !== auth) changed = true
        val decodedAfter = decodePercentRuns(after, wide)
        if (decodedAfter !== after) changed = true
        if (!changed) return url
        return url.substring(0, schemeEnd + 3) + decodedAuth + decodedAfter
    }

    private fun decodeAuthority(authority: String, wide: Boolean): String {
        val hostport = authority.substringAfterLast('@')
        val prefix = if ('@' in authority) authority.substring(0, authority.length - hostport.length) else ""
        if (hostport.startsWith("[")) return authority // IPv6 literal
        if (hostport.count { it == ':' } > 1) return authority
        val host = hostport.substringBefore(':')
        val suffix = if (':' in hostport) hostport.substring(host.length) else ""
        if ('.' !in host && !host.startsWith("xn--", ignoreCase = true)) return authority
        var changed = false
        val decoded = host.split('.').joinToString(".") { label ->
            val decodedLabel = decodeLabel(label, wide)
            if (decodedLabel !== label) changed = true
            decodedLabel
        }
        if (!changed) return authority
        return prefix + decoded + suffix
    }

    private fun decodeLabel(label: String, wide: Boolean): String {
        if (!label.startsWith("xn--", ignoreCase = true) || label.length <= 4) return label
        val decoded = try {
            IDN.toUnicode(label)
        } catch (e: Exception) {
            return label
        }
        if (decoded == label || !decoded.any { it.code >= 0x80 }) return label
        return if (isAllowedHost(decoded, wide)) decoded else label
    }

    private fun isAllowedHost(decoded: String, wide: Boolean): Boolean {
        var i = 0
        while (i < decoded.length) {
            val cp = decoded.codePointAt(i)
            if (cp >= 0x80) {
                if (wide) {
                    if (isWideBlocked(cp)) return false
                } else if (!isNarrow(cp)) {
                    return false
                }
            }
            i += Character.charCount(cp)
        }
        return true
    }

    private fun decodePercentRuns(text: String, wide: Boolean): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        var changed = false
        while (i < text.length) {
            val run = readPercentRun(text, i)
            if (run == null) {
                out.append(text[i])
                i++
                continue
            }
            val decoded = decodeRunBytes(run, wide)
            if (decoded != null) {
                out.append(decoded)
                changed = true
            } else {
                out.append(text, i, run.endIndex)
            }
            i = run.endIndex
        }
        if (!changed) return text
        return out.toString()
    }

    private data class Run(val bytes: ByteArray, val endIndex: Int)

    private fun readPercentRun(text: String, start: Int): Run? {
        if (start + 2 >= text.length || text[start] != '%') return null
        val bytes = ArrayList<Byte>(8)
        var i = start
        while (i + 2 < text.length && text[i] == '%') {
            val hi = text[i + 1].digitToIntOrNull(16) ?: break
            val lo = text[i + 2].digitToIntOrNull(16) ?: break
            val byte = ((hi shl 4) or lo).toByte()
            // stop the run at the first ASCII byte: decoding those could rewrite URL structure
            if (byte.toInt() and 0xFF < 0x80) break
            bytes.add(byte)
            i += 3
        }
        if (bytes.isEmpty()) return null
        return Run(bytes.toByteArray(), i)
    }

    private fun decodeRunBytes(run: Run, wide: Boolean): String? {
        val decoded = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(run.bytes)).toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        var i = 0
        while (i < decoded.length) {
            val cp = decoded.codePointAt(i)
            if (wide) {
                if (isWideBlocked(cp)) return null
            } else if (!isNarrow(cp)) {
                return null
            }
            i += Character.charCount(cp)
        }
        return decoded
    }

    private fun isNarrow(cp: Int): Boolean =
        cp in 0x80..0x24F || cp in 0x1E00..0x1EFF || cp in 0x400..0x4FF

    private fun isWideBlocked(cp: Int): Boolean {
        if (cp == 0xFFFD) return true
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SPACE_SEPARATOR.toInt() -> true
            else -> false
        }
    }
}
