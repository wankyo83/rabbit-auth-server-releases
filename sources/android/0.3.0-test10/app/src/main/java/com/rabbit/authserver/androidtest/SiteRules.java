package com.rabbit.authserver.androidtest;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** URL allowlist shared by automatic jobs and the manual authentication screen. */
final class SiteRules {
    private static final Pattern TOKI_HOST = Pattern.compile("(?:newtoki\\d+\\.org|toki\\d+\\.com|sbxh\\d+\\.com)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKI_IMAGES = Pattern.compile("/(?:webtoon|manhwa)/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/?");
    private static final Pattern TOKI_NOVEL = Pattern.compile("/novel/\\d+/[^/]+/?");
    private static final Pattern BLACKTOON_HOST = Pattern.compile("blacktoon\\d+\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern JJAPTOON_HOST = Pattern.compile("(?:www\\.)?jjaptoon\\d+\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOON11_HOST = Pattern.compile("11toon\\d*\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern WFWF_HOST = Pattern.compile("wfwf\\d+\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOODTOON_HOST = Pattern.compile("(?:www\\.)?goodtoon\\d+\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOODTOON_PATH = Pattern.compile("/manga/[A-Za-z0-9_-]+/(?:chapter-)?\\d+/?");
    private static final Pattern NEWXTOON_HOST = Pattern.compile("(?:www\\.)?newxtoon\\d+\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEWXTOON_PATH = Pattern.compile("/comics/\\d+/chapters/\\d+/?");

    private SiteRules() {}

    static Target chapter(String value, String kind) throws RuleException {
        if (!("images".equals(kind) || "novel".equals(kind))) throw new RuleException("invalid_kind");
        try {
            if (value == null || value.length() > 8192) throw new IllegalArgumentException();
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null ||
                uri.getFragment() != null || host.isBlank() || path == null || path.isBlank()) {
                throw new IllegalArgumentException();
            }

            String family;
            if (TOKI_HOST.matcher(host).matches()) {
                family = "novel".equals(kind) ? "novel" : "toki";
                boolean imagePath = TOKI_IMAGES.matcher(path).matches();
                boolean novelPath = TOKI_NOVEL.matcher(path).matches();
                if (uri.getQuery() != null || !(imagePath || novelPath)) throw new RuleException("chapter_path_not_allowed");
                if (("novel".equals(kind)) != novelPath) throw new RuleException("chapter_kind_mismatch");
            } else if (BLACKTOON_HOST.matcher(host).matches()) {
                family = "blacktoon";
            } else if (JJAPTOON_HOST.matcher(host).matches()) {
                family = "jjaptoon";
            } else if (TOON11_HOST.matcher(host).matches()) {
                family = "toon11";
            } else if (WFWF_HOST.matcher(host).matches()) {
                family = "wfwf";
            } else if (GOODTOON_HOST.matcher(host).matches()) {
                family = "goodtoon";
                if (uri.getQuery() != null || !GOODTOON_PATH.matcher(path).matches()) throw new RuleException("chapter_path_not_allowed");
            } else if (NEWXTOON_HOST.matcher(host).matches()) {
                family = "newxtoon";
                if (uri.getQuery() != null || !NEWXTOON_PATH.matcher(path).matches()) throw new RuleException("chapter_path_not_allowed");
            } else if (host.equals("comic.naver.com") || host.equals("m.comic.naver.com")) {
                family = "naver";
            } else {
                throw new RuleException("unsupported_site");
            }

            if ("novel".equals(kind) && !"novel".equals(family)) throw new RuleException("unsupported_novel_site");
            String origin = uri.getScheme() + "://" + host;
            return new Target(uri.toString(), family, kind, origin);
        } catch (RuleException error) {
            throw error;
        } catch (Exception error) {
            throw new RuleException("chapter_url_not_allowed");
        }
    }

    static boolean allowedForManualView(String value) {
        try {
            chapter(value, "images");
            return true;
        } catch (RuleException ignored) {
            try {
                chapter(value, "novel");
                return true;
            } catch (RuleException ignoredAgain) {
                return false;
            }
        }
    }

    static String referer(Target target) {
        return "newxtoon".equals(target.family) ? target.url : target.origin + "/";
    }

    record Target(String url, String family, String kind, String origin) {}

    static final class RuleException extends Exception {
        final String code;
        RuleException(String code) {
            super(code);
            this.code = code;
        }
    }
}
