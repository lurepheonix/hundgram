package desu.inugram.core.urldisplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class IdnUrlDecoderTest {

    @Test
    fun narrow_decodes_cyrillic_punycode_host() {
        assertEquals(
            "https://президент.рф/новости",
            IdnUrlDecoder.decodeNarrow("https://xn--d1abbgf6aiiy.xn--p1ai/%D0%BD%D0%BE%D0%B2%D0%BE%D1%81%D1%82%D0%B8"),
        )
    }

    @Test
    fun narrow_decodes_slavic_latin_host() {
        assertEquals(
            "https://česká.example.com/",
            IdnUrlDecoder.decodeNarrow("https://xn--esk-gla0t.example.com/"),
        )
    }

    @Test
    fun narrow_leaves_cjk_punycode_host_encoded() {
        val url = "https://xn--fiqs8s.example.com/"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
    }

    @Test
    fun wide_decodes_cjk_punycode_host() {
        assertEquals(
            "https://中国.example.com/",
            IdnUrlDecoder.decodeWide("https://xn--fiqs8s.example.com/"),
        )
    }

    @Test
    fun narrow_decodes_cyrillic_percent_path_but_leaves_cjk_run() {
        assertEquals(
            "https://example.com/путь/%E4%B8%AD%E5%9B%BD",
            IdnUrlDecoder.decodeNarrow("https://example.com/%D0%BF%D1%83%D1%82%D1%8C/%E4%B8%AD%E5%9B%BD"),
        )
    }

    @Test
    fun wide_decodes_cjk_percent_path() {
        assertEquals(
            "https://example.com/中国",
            IdnUrlDecoder.decodeWide("https://example.com/%E4%B8%AD%E5%9B%BD"),
        )
    }

    @Test
    fun structural_percent_triples_stay_encoded_in_both_modes() {
        val url = "https://example.com/%D0%BF%D1%83%D1%82%D1%8C%2F%D1%81%D1%82%D1%80"
        assertEquals("https://example.com/путь%2Fстр", IdnUrlDecoder.decodeNarrow(url))
        assertEquals("https://example.com/путь%2Fстр", IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun ascii_percent_triples_stay_encoded() {
        val url = "https://example.com/%41%42"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun run_before_ascii_triple_decodes_and_leaves_the_triple() {
        assertEquals(
            "https://example.com/п%41",
            IdnUrlDecoder.decodeNarrow("https://example.com/%D0%BF%41"),
        )
    }

    @Test
    fun malformed_percent_runs_pass_through() {
        val url = "https://example.com/100%d1%zz%D0"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun broken_utf8_run_stays_encoded() {
        val url = "https://example.com/%FF%FE"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun wide_refuses_bidi_override_in_display() {
        // U+202E decoded into display text would enable visual spoofing
        val url = "https://example.com/%E2%80%AEabc"
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun wide_refuses_encoded_space_in_display() {
        // U+00A0 non-breaking space
        val url = "https://example.com/%C2%A0"
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun already_unicode_url_is_returned_as_is() {
        val url = "https://президент.рф/путь?x=1#frag"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun plain_ascii_url_is_returned_as_is() {
        val url = "https://example.com/path?q=1"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun non_http_scheme_is_untouched() {
        val url = "tg://resolve?domain=xn--p1ai"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
        assertSame(url, IdnUrlDecoder.decodeWide(url))
    }

    @Test
    fun invalid_punycode_label_is_untouched() {
        val url = "https://xn--/path"
        assertSame(url, IdnUrlDecoder.decodeNarrow(url))
    }

    @Test
    fun port_userinfo_and_query_survive_host_decode() {
        assertEquals(
            "https://user@президент.рф:8080/п?х=1",
            IdnUrlDecoder.decodeNarrow("https://user@xn--d1abbgf6aiiy.xn--p1ai:8080/%D0%BF?%D1%85=1"),
        )
    }
}
