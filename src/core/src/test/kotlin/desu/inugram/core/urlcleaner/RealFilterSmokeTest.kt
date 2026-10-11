package desu.inugram.core.urlcleaner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke test against the actual AdGuard URL Tracking filter (list 17).
 *
 * Asserts structural invariants only — never exact rule contents. Upstream
 * restructures the list at will (e.g. dropping broad global rules), so any
 * exact-output assertion here is a future red build. Exact stripping behavior
 * is pinned by [UrlCleanerTest] with inline fixtures instead.
 *
 * Skipped silently if the file isn't present locally.
 */
class RealFilterSmokeTest {
    private fun loadCleaner(): UrlCleaner? {
        val candidates = listOfNotNull(
            System.getProperty("adguard.filter17"),
            // src/core symlinked into worktree/InuCore — both resolve back to the fork source dir
            "../../src/res/assets/adguard_url_tracking.txt",
            "src/res/assets/adguard_url_tracking.txt",
        )
        val f = candidates.map(::File).firstOrNull { it.exists() } ?: return null
        val text = f.readText()
        if (text.isBlank()) return null
        return UrlCleaner.fromAdGuardFilter(text)
    }

    /**
     * clean() only ever drops query params (or returns the input untouched):
     * scheme, host, path and fragment survive, and kept params are a subset
     * of the originals — regardless of what the loaded list contains.
     */
    @Test fun real_list_cleans_without_changing_url_structure() {
        val c = loadCleaner() ?: return
        val urls = listOf(
            "https://example.com/page?utm_source=newsletter&utm_medium=email&id=42",
            "https://news.example.com/article?fbclid=abc123&page=2",
            "https://shop.example.com/item?srsltid=AfmBOoq&color=red",
            "https://example.com/article?mc_cid=abc&mc_eid=xyz&id=1",
            "https://glavnoe.life/news?utm_campaign=keep&fbclid=drop",
            "https://lifehacker.ru/post?erid=keep&utm_source=drop",
            "https://example.com/?page=2&sort=date",
            "https://example.com/no-query",
            "https://example.com/empty?",
            "https://example.com/path?a=1#section",
            "https://example.com/#frag",
        )
        for (url in urls) {
            val cleaned = c.clean(url)
            assertEquals(url.substringBefore('?'), cleaned.substringBefore('?'), "non-query part changed: $url -> $cleaned")
            assertEquals(
                url.substringAfter('#', "<none>"),
                cleaned.substringAfter('#', "<none>"),
                "fragment changed: $url -> $cleaned",
            )
            val originalParams = url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }
            val keptParams = cleaned.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }
            assertTrue(keptParams.all { it in originalParams }, "clean added params: $url -> $cleaned")
        }
    }

    @Test fun real_list_leaves_unparseable_input_alone() {
        val c = loadCleaner() ?: return
        for (url in listOf("not a url", "", "://missing-scheme", "https://")) {
            assertEquals(url, c.clean(url))
        }
    }

    /**
     * Guards against a dead list (empty file, downloaded error page, or a
     * parser regression dropping every rule): a tracking filter that strips
     * nothing at all is useless. Probes several common params so the loss of
     * any single upstream rule can't fail this.
     */
    @Test fun real_list_strips_at_least_one_known_tracker() {
        val c = loadCleaner() ?: return
        val probes = listOf(
            "https://example.com/?fbclid=x",
            "https://example.com/?gclid=x",
            "https://example.com/?utm_source=x",
            "https://example.com/?srsltid=x",
            "https://example.com/?mc_cid=x",
            "https://example.com/?yclid=x",
            "https://example.com/?msclkid=x",
        )
        assertTrue(probes.any { c.clean(it) != it }, "live list stripped none of the probe params")
    }
}
