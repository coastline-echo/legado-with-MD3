package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BookSourceUrlNormalizerTest {
    @Test fun normalizesHostMarkerAndTrailingSlash() {
        val identity = normalizeBookSourceUrl(" \u0000 HTTP://API.Example.com/##@曦灵 \n")
        assertEquals("http://api.example.com", identity?.normalizedUrl)
        assertEquals("api.example.com", identity?.host)
    }

    @Test fun preservesProtocolPortPathAndQuery() {
        assertEquals("http://example.com/path?a=/", normalizeBookSourceUrl("http://example.com/path?a=/")?.normalizedUrl)
        assertNotEquals(
            normalizeBookSourceUrl("http://example.com/path?a=1"),
            normalizeBookSourceUrl("https://example.com/path?a=1")
        )
        assertNotEquals(
            normalizeBookSourceUrl("http://example.com:80/path"),
            normalizeBookSourceUrl("http://example.com:81/path")
        )
        assertNotEquals(
            normalizeBookSourceUrl("http://example.com/a"),
            normalizeBookSourceUrl("http://example.com/b")
        )
    }

    @Test fun invalidAndEmptyUrlsReturnNull() {
        assertNull(normalizeBookSourceUrl(""))
        assertNull(normalizeBookSourceUrl("not a url"))
        assertNull(normalizeBookSourceUrl("https?://api.example.com/(?!.*newsearch)."))
    }

    @Test fun stripsBookSourceAnnotations() {
        assertEquals("https://www.txtduo.net", normalizeBookSourceUrl("https://www.txtduo.net/##Rosiapng")?.normalizedUrl)
        assertEquals("https://m.kanman.com", normalizeBookSourceUrl("https://m.kanman.com已校验")?.normalizedUrl)
        assertEquals("https://api-bc.wtzw.com", normalizeBookSourceUrl("https://api-bc.wtzw.com＃妍希")?.normalizedUrl)
    }

    @Test fun acceptsLegacyBookSourceAddressesForImport() {
        assertEquals("https://novel.html5.qq.com", normalizeBookSourceUrl("novel.html5.qq.com")?.normalizedUrl)
        assertEquals(
            "http://m.gulongbbs.com/wuxia",
            normalizeBookSourceUrl("http://m.gulongbbs.com/wuxia/")?.normalizedUrl
        )
        assertEquals("http://m.zhangyue.com", normalizeBookSourceUrl("http://m.zhangyue.com")?.normalizedUrl)
        assertEquals(
            "https://网易云音乐.luoyacheng.ip-ddns.com",
            normalizeBookSourceUrl("https://\n网易云音乐.luoyacheng.ip-ddns.com")?.normalizedUrl
        )
        assert(isUsableBookSourceUrl("novel.html5.qq.com"))
        assert(!isUsableBookSourceUrl("https?://example.com"))
    }

    @Test fun classifiesConflictByFullHostName() {
        val local = listOf("http://api.example.com/")
        assertEquals(BookSourceUrlConflict.Normalized, classifyBookSourceUrlConflict("http://api.example.com", local))
        assertEquals(BookSourceUrlConflict.SameHost, classifyBookSourceUrlConflict("http://api.example.com/books", local))
        assertEquals(BookSourceUrlConflict.None, classifyBookSourceUrlConflict("http://www.example.com", local))
    }
}
