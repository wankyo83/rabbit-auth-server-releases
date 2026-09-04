package com.rabbit.authserver.androidtest;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class JobManager implements AutoCloseable {
    private static final long JOB_TTL_MS = 30 * 60_000L;
    private static final String PENDING_IMAGE = "https://raw.githubusercontent.com/wankyo83/rabbit-auth-server-releases/main/assets/source-upload-pending.png";
    private static final String LOCKED_IMAGE = "https://raw.githubusercontent.com/wankyo83/rabbit-auth-server-releases/main/assets/source-paid-locked.png";
    private final Context context;
    private final WebAuthEngine engine;
    private final ExecutorService worker = Executors.newFixedThreadPool(AppConfig.CONCURRENCY);
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    JobManager(Context context, WebAuthEngine engine) {
        this.context = context.getApplicationContext();
        this.engine = engine;
    }

    synchronized JSONObject create(String url, String requestId, String kind) throws JobException {
        cleanup();
        try { UUID.fromString(requestId); } catch (Exception error) {
            throw new JobException(400, "request_id_invalid", "requestId는 UUID여야 합니다.");
        }
        SiteRules.Target target;
        try {
            target = SiteRules.chapter(url, kind);
        } catch (SiteRules.RuleException error) {
            throw new JobException(400, error.code, "지원하지 않는 회차 주소입니다.");
        }
        if (jobs.size() >= 32) throw new JobException(409, "too_many_jobs", "열려 있는 작업이 너무 많습니다.");
        Job job = new Job(UUID.randomUUID().toString(), target);
        jobs.put(job.id, job);
        AppState.job(context, target.url(), "대기 중 · " + shortUrl(target.url()));
        worker.execute(() -> process(job));
        return job.statusJson();
    }

    synchronized JSONObject status(String id) throws JobException {
        return requireJob(id).statusJson();
    }

    synchronized JSONObject manifest(String id) throws JobException {
        Job job = requireJob(id);
        if (!"ready".equals(job.state)) throw new JobException(409, "job_not_ready", "작업이 아직 완료되지 않았습니다.");
        WebAuthEngine.Result result = job.result;
        JSONObject manifest = object("id", job.id, "chapterUrl", job.target.url(), "kind", result.kind);
        if ("novel".equals(result.kind)) {
            try {
                manifest.put("text", result.text);
                manifest.put("title", result.title);
            } catch (org.json.JSONException ignored) {}
            return manifest;
        }

        try {
            manifest.put("expected", result.pages.size());
            manifest.put("referer", result.referer);
            manifest.put("userAgent", result.userAgent);
        } catch (org.json.JSONException ignored) {}
        JSONArray pages = new JSONArray();
        for (WebAuthEngine.Page page : result.pages) pages.put(object("page", page.number, "urls", new JSONArray(page.urls)));
        try { manifest.put("pages", pages); } catch (org.json.JSONException ignored) {}
        return manifest;
    }

    synchronized JSONObject closeJob(String id) throws JobException {
        Job job = requireJob(id);
        job.state = "closed";
        jobs.remove(id);
        return object("id", id, "state", "closed");
    }

    private void process(Job job) {
        job.state = "authenticating";
        AppState.job(context, job.target.url(), "인증 및 내용 확인 중 · " + shortUrl(job.target.url()));
        try {
            WebAuthEngine.Result result = engine.extract(job.target, 80_000L);
            if (result.locked) {
                result = WebAuthEngine.Result.images(
                    List.of(new WebAuthEngine.Page(1, List.of(LOCKED_IMAGE))),
                    job.target.origin() + "/",
                    result.userAgent,
                    false
                );
                AppState.job(context, job.target.url(), "원본 사이트 잠긴 회차 · 안내 이미지 반환");
            } else if (result.pending) {
                result = WebAuthEngine.Result.images(
                    List.of(new WebAuthEngine.Page(1, List.of(PENDING_IMAGE))),
                    job.target.origin() + "/",
                    result.userAgent,
                    true
                );
                AppState.job(context, job.target.url(), "원본 사이트 이미지 처리 중 · 안내 이미지 반환");
            } else if ("novel".equals(result.kind)) {
                AppState.job(context, job.target.url(), "완료 · 소설 본문 확인");
            } else {
                AppState.job(context, job.target.url(), "완료 · 이미지 " + result.pages.size() + "장");
            }
            job.result = validateResult(result);
            job.state = "ready";
        } catch (Exception error) {
            job.errorCode = error instanceof WebAuthEngine.EngineException engineError
                ? engineError.code : error instanceof JobException jobError ? jobError.code : "android_auth_failed";
            job.errorMessage = safeMessage(error);
            job.state = "failed";
            AppState.job(context, job.target.url(), "실패 · " + job.errorCode + " · 인증 화면에서 확인 가능");
        }
    }

    private static WebAuthEngine.Result validateResult(WebAuthEngine.Result result) throws JobException {
        if ("novel".equals(result.kind)) {
            if (result.text == null || result.text.trim().length() < 30 || result.text.length() > 1_000_000) {
                throw new JobException(400, "invalid_novel_text", "소설 본문이 올바르지 않습니다.");
            }
            return WebAuthEngine.Result.novel(result.text, result.title == null ? "" : result.title);
        }
        if (!"images".equals(result.kind) || result.pages.isEmpty() || result.pages.size() > 2000) {
            throw new JobException(400, "invalid_page_count", "이미지 수가 올바르지 않습니다.");
        }
        List<WebAuthEngine.Page> sorted = new ArrayList<>(result.pages);
        sorted.sort(Comparator.comparingInt(page -> page.number));
        List<WebAuthEngine.Page> normalized = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            WebAuthEngine.Page page = sorted.get(index);
            if (page.number != index + 1 || page.urls.isEmpty() || page.urls.size() > 8) {
                throw new JobException(400, "invalid_page_order", "이미지 순서가 올바르지 않습니다.");
            }
            LinkedHashSet<String> safe = new LinkedHashSet<>();
            for (String value : page.urls) {
                try {
                    URI uri = URI.create(value);
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
                    safe.add(value);
                } catch (Exception error) {
                    throw new JobException(400, "invalid_image_url", "안전하지 않은 이미지 주소입니다.");
                }
            }
            normalized.add(new WebAuthEngine.Page(index + 1, List.copyOf(safe)));
        }
        return WebAuthEngine.Result.images(normalized, result.referer, result.userAgent, result.pending);
    }

    private synchronized Job requireJob(String id) throws JobException {
        cleanup();
        try { UUID.fromString(id); } catch (Exception error) {
            throw new JobException(404, "job_not_found", "작업을 찾을 수 없습니다.");
        }
        Job job = jobs.get(id);
        if (job == null) throw new JobException(404, "job_not_found", "작업을 찾을 수 없습니다.");
        return job;
    }

    private synchronized void cleanup() {
        long now = System.currentTimeMillis();
        var iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (now - job.lastAccessAt > JOB_TTL_MS || "closed".equals(job.state)) {
                iterator.remove();
            }
        }
    }

    private static String shortUrl(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        return uri.getHost() + (path.length() > 42 ? path.substring(0, 42) + "…" : path);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static JSONObject object(Object... entries) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index + 1 < entries.length; index += 2) result.put(String.valueOf(entries[index]), entries[index + 1]);
        } catch (org.json.JSONException ignored) {}
        return result;
    }

    @Override public void close() {
        worker.shutdownNow();
        synchronized (this) {
            jobs.clear();
        }
    }

    static final class JobException extends Exception {
        final int httpStatus;
        final String code;
        JobException(int httpStatus, String code, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.code = code;
        }
    }

    private static final class Job {
        final String id;
        final SiteRules.Target target;
        final long createdAt = System.currentTimeMillis();
        volatile long lastAccessAt = createdAt;
        volatile String state = "queued";
        volatile String errorCode;
        volatile String errorMessage;
        volatile WebAuthEngine.Result result;

        Job(String id, SiteRules.Target target) {
            this.id = id;
            this.target = target;
        }

        JSONObject statusJson() {
            JSONObject json = object("id", id, "state", state, "site", URI.create(target.url()).getHost(), "kind", target.kind());
            if (result != null && "images".equals(result.kind)) {
                try { json.put("expected", result.pages.size()); } catch (org.json.JSONException ignored) {}
            }
            if ("failed".equals(state)) {
                try { json.put("error", errorCode); } catch (org.json.JSONException ignored) {}
            }
            return json;
        }
    }
}
