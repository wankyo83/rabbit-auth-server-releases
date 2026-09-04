package com.rabbit.authserver.androidtest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SiteRulesTest {
    @Test public void supportsEveryDesktopServerImageFamily() throws Exception {
        assertTarget("https://newtoki1.org/webtoon/a/b", "images", "toki");
        assertTarget("https://toki31.com/manhwa/a/b", "images", "toki");
        assertTarget("https://sbxh31.com/webtoon/a/b", "images", "toki");
        assertTarget("https://blacktoon123.com/webtoon/123", "images", "blacktoon");
        assertTarget("https://www.jjaptoon12.com/webtoon/123", "images", "jjaptoon");
        assertTarget("https://11toon12.com/webtoon/123", "images", "toon11");
        assertTarget("https://wfwf12.com/webtoon/123", "images", "wfwf");
        assertTarget("https://www.goodtoon002.com/manga/gt-14556/chapter-2067", "images", "goodtoon");
        assertTarget("https://newxtoon1.com/comics/12/chapters/34", "images", "newxtoon");
        assertTarget("https://comic.naver.com/webtoon/detail?titleId=1&no=2", "images", "naver");
    }

    @Test public void supportsNovelOnlyWithNovelKind() throws Exception {
        assertTarget("https://newtoki1.org/novel/123/chapter-name", "novel", "novel");
        rejected("https://newtoki1.org/novel/123/chapter-name", "images", "chapter_kind_mismatch");
        rejected("https://newtoki1.org/webtoon/a/b", "novel", "chapter_kind_mismatch");
    }

    @Test public void rejectsUnknownAndMalformedTargets() throws Exception {
        rejected("http://newtoki1.org/webtoon/a/b", "images", "chapter_url_not_allowed");
        rejected("https://evil.example/webtoon/a/b", "images", "unsupported_site");
        rejected("https://newtoki1.org/webtoon/a/b?token=1", "images", "chapter_path_not_allowed");
        rejected("https://www.goodtoon002.com/other/1", "images", "chapter_path_not_allowed");
        rejected("https://newxtoon1.com/comics/12", "images", "chapter_path_not_allowed");
        rejected("https://newtoki1.org/webtoon/a/b", "video", "invalid_kind");
    }

    @Test public void usesChapterRefererOnlyForXtoon() throws Exception {
        SiteRules.Target xtoon = SiteRules.chapter("https://newxtoon1.com/comics/12/chapters/34", "images");
        SiteRules.Target toki = SiteRules.chapter("https://newtoki1.org/webtoon/a/b", "images");
        assertEquals(xtoon.url(), SiteRules.referer(xtoon));
        assertEquals("https://newtoki1.org/", SiteRules.referer(toki));
    }

    private static void assertTarget(String url, String kind, String family) throws Exception {
        SiteRules.Target target = SiteRules.chapter(url, kind);
        assertEquals(url, target.url());
        assertEquals(kind, target.kind());
        assertEquals(family, target.family());
        assertTrue(SiteRules.allowedForManualView(url));
    }

    private static void rejected(String url, String kind, String code) throws Exception {
        try {
            SiteRules.chapter(url, kind);
            fail("Expected " + code);
        } catch (SiteRules.RuleException error) {
            assertEquals(code, error.code);
        }
    }
}
