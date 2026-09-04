package com.rabbit.authserver.androidtest;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class WebAuthEngine implements AutoCloseable {
    private static final String TAG = "RabbitWebAuth";
    interface ManualAuthListener { void onManualAuthRequired(String url); }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ManualAuthListener listener;
    private final Set<Session> sessions = Collections.synchronizedSet(Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    private final Object warmLock = new Object();
    private final ArrayDeque<Session> warmSessions = new ArrayDeque<>();
    private volatile boolean closed;

    WebAuthEngine(Context context, ManualAuthListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        CookieManager.getInstance().setAcceptCookie(true);
        WebView.setWebContentsDebuggingEnabled(true);
        for (int index = 0; index < AppConfig.CONCURRENCY; index++) prepareAsync(index + 1);
    }

    Result extract(SiteRules.Target target, long timeoutMs) throws EngineException {
        if (closed) throw new EngineException("engine_closed", "인증 엔진이 종료되었습니다.");
        Session session = acquireSession();
        session.pageFinished = false;
        session.gestureSent = false;
        session.lastMainError = "사이트 응답을 확인하지 못했습니다.";
        try {
            runOnMain(() -> session.webView.loadUrl(target.url()));
            long startedAt = System.currentTimeMillis();
            long deadline = startedAt + timeoutMs;
            long stableSince = 0;
            long lastDiagnosticAt = 0;
            String lastSignature = "";
            Snapshot best = null;

            while (!closed && System.currentTimeMillis() < deadline) {
                Snapshot snapshot = evaluate(session.webView, target.family());
                if (snapshot != null) {
                    if (System.currentTimeMillis() - lastDiagnosticAt >= 5_000L) {
                        lastDiagnosticAt = System.currentTimeMillis();
                        Log.i(TAG, "family=" + target.family() + " expected=" + snapshot.expected + " pages=" + snapshot.pages.size() +
                            " allImages=" + snapshot.allImages + " viewport=" + snapshot.viewport + " document=" + snapshot.documentHeight +
                            " href=" + snapshot.href);
                    }
                    if (!snapshot.error.isBlank()) throw new EngineException(snapshot.error, "사이트에서 로그인 또는 이용 권한 확인이 필요합니다.");
                    if (snapshot.pending) {
                        CookieManager.getInstance().flush();
                        return Result.images(List.of(), SiteRules.referer(target), session.userAgent, true);
                    }
                    if ("novel".equals(target.kind()) && snapshot.text.trim().length() > 30) {
                        CookieManager.getInstance().flush();
                        return Result.novel(snapshot.text, snapshot.title);
                    }
                    if (!snapshot.pages.isEmpty()) {
                        best = snapshot;
                        String signature = snapshot.expected + ":" + snapshot.pages;
                        if (signature.equals(lastSignature)) {
                            if (stableSince == 0) stableSince = System.currentTimeMillis();
                        } else {
                            lastSignature = signature;
                            stableSince = System.currentTimeMillis();
                        }
                        if (ExtractionCompletionPolicy.complete(
                            snapshot.expected,
                            snapshot.pages.size(),
                            session.pageFinished,
                            System.currentTimeMillis() - stableSince
                        )) {
                            CookieManager.getInstance().flush();
                            return Result.images(snapshot.pages, SiteRules.referer(target), session.userAgent, false);
                        }
                    }
                }
                if (!session.gestureSent && session.pageFinished && "images".equals(target.kind()) &&
                    System.currentTimeMillis() - startedAt >= 1_500L &&
                    (snapshot == null || snapshot.pages.isEmpty())) {
                    session.gestureSent = true;
                    dispatchShortGesture(session);
                }
                sleep(200);
            }

            if (closed) throw new EngineException("engine_closed", "인증 엔진이 종료되었습니다.");
            AppState.job(context, target.url(), "자동 인증 대기 시간 초과 · 인증 화면을 열어 주세요");
            listener.onManualAuthRequired(target.url());
            String detail = best == null ? session.lastMainError : "확인된 이미지 " + best.pages.size() + "장";
            throw new EngineException("manual_viewer_confirmation_required", "자동 확인을 완료하지 못했습니다. " + detail);
        } finally {
            recycleSession(session);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void configure(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " RabbitAuthAndroidTest/" + BuildConfig.VERSION_NAME);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
    }

    private Session createSession() throws EngineException {
        AtomicReference<Session> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        main.post(() -> {
            VirtualWebViewDisplay display = null;
            try {
                display = VirtualWebViewDisplay.create(context, main);
                WebView webView = display.webView;
                configure(webView);
                Session session = new Session(display, webView.getSettings().getUserAgentString());
                webView.setWebChromeClient(new WebChromeClient());
                webView.setWebViewClient(new WebViewClient() {
                    @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { session.pageFinished = false; }
                    @Override public void onPageFinished(WebView view, String url) {
                        session.pageFinished = true;
                        CookieManager.getInstance().flush();
                    }
                    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                        if (request.isForMainFrame()) session.lastMainError = "HTTP 연결 오류 " + error.getErrorCode();
                    }
                    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                        if (request.isForMainFrame()) session.lastMainError = "HTTP " + response.getStatusCode();
                    }
                    @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                        session.lastMainError = "SSL 인증서 오류";
                        handler.cancel();
                    }
                });
                sessions.add(session);
                Log.i(TAG, "virtual display ready " + display.geometry.summary());
                result.set(session);
            } catch (Throwable error) {
                if (display != null) display.close();
                failure.set(error);
            } finally {
                ready.countDown();
            }
        });
        await(ready, 10_000, "webview_create_timeout");
        if (failure.get() != null) throw new EngineException("webview_create_failed", safeMessage(failure.get()));
        return result.get();
    }

    private void prepareAsync(int slot) {
        Thread thread = new Thread(() -> {
            Session prepared = null;
            try {
                if (!closed) prepared = createSession();
            } catch (EngineException error) {
                Log.w(TAG, "WebView prewarm failed: " + error.code);
                return;
            }
            synchronized (warmLock) {
                if (!closed && warmSessions.size() < AppConfig.CONCURRENCY) {
                    warmSessions.addLast(prepared);
                    prepared = null;
                    Log.i(TAG, "virtual display prewarmed slot=" + slot);
                }
            }
            if (prepared != null) destroy(prepared);
        }, "rabbit-webview-prewarm-" + slot);
        thread.setDaemon(true);
        thread.start();
    }

    private Session acquireSession() throws EngineException {
        synchronized (warmLock) {
            if (closed) throw new EngineException("engine_closed", "인증 엔진이 종료되었습니다.");
            if (!warmSessions.isEmpty()) {
                Session ready = warmSessions.removeFirst();
                Log.i(TAG, "warm virtual display acquired");
                return ready;
            }
        }
        return createSession();
    }

    private void recycleSession(Session session) {
        try {
            runOnMain(() -> {
                session.webView.stopLoading();
                session.webView.loadUrl("about:blank");
                session.webView.clearHistory();
            });
        } catch (EngineException error) {
            destroy(session);
            return;
        }
        session.pageFinished = false;
        session.gestureSent = false;
        session.lastMainError = "사이트 응답을 확인하지 못했습니다.";
        synchronized (warmLock) {
            if (!closed && warmSessions.size() < AppConfig.CONCURRENCY) {
                warmSessions.addLast(session);
                Log.i(TAG, "virtual display recycled");
            } else {
                destroy(session);
            }
        }
    }

    private Snapshot evaluate(WebView webView, String family) throws EngineException {
        String script = "(() => {" +
            "const family='" + family + "';" +
            "const text=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ').trim();" +
            "if(family==='toki'&&text.includes('이미지 처리 중인 회차입니다. 잠시 후 다시 확인해주세요.'))return JSON.stringify({pending:true});" +
            "if(family==='novel'){" +
            "const viewer=document.querySelector('.novel-viewer');" +
            "const alert=document.querySelector('[data-novel-unlock-status], .novel-gate, .novel-error, .novel-viewer [role=alert]');" +
            "if(/구매가 필요|잠긴 회차|로그인이 필요|이용할 수 없는 회차/.test(alert&&alert.innerText||''))return JSON.stringify({error:'manual_login_or_paid_content'});" +
            "let root=viewer;for(const node of [viewer,...Array.from(viewer&&viewer.querySelectorAll('*')||[])]){if(node&&(node.shadowRoot||node.__novelShadow)){root=node.shadowRoot||node.__novelShadow;break}}" +
            "const paragraphs=Array.from(root&&root.querySelectorAll('p')||[]).map(p=>p.innerText||p.textContent||'').filter(t=>t.trim());" +
            "return JSON.stringify({text:paragraphs.join('\\n\\n'),title:document.querySelector('.ne-h1, .novel-viewer h1')?.textContent||document.title||'',allImages:document.images.length,viewport:innerHeight,documentHeight:document.documentElement.scrollHeight,href:location.href});}" +
            "let rows=[];let expected=0;" +
            "if(family==='toon11'){const scripts=Array.from(document.querySelectorAll('script:not([src])')).map(s=>s.textContent||'').join('\\n');" +
            "const parse=name=>{const m=scripts.match(new RegExp(name+'\\\\s*=\\\\s*(\\\\[[\\\\s\\\\S]*?\\\\])'));if(!m)return [];try{const v=JSON.parse(m[1]);return Array.isArray(v)?v:[]}catch(e){return []}};" +
            "const one=parse('img_list'),two=parse('img_list_2');if(one.length){rows=one.map((src,i)=>({page:i+1,urls:[src,two[i]].filter(v=>typeof v==='string'&&v)}));expected=one.length}}" +
            "if(!rows.length){const selectors={toki:'.theme-viewer-images img, .vw-imgs img, img.viewer-ratio-img',blacktoon:'#toon_content_imgs img',jjaptoon:'[data-reading-image-index] > img',wfwf:'.viewer-wrap img[data-src]',goodtoon:'.reading-content img, div.page-break img',newxtoon:'#comic-reader [data-reader-page] img[data-reader-image][src]',naver:'.wt_viewer img, .toon_view_lst img',toon11:'#comic-viewer img, #toon_content_imgs img'};" +
            "const nodes=Array.from(document.querySelectorAll(selectors[family]||'NOT_A_VIEWER'));const seen=new Set();nodes.forEach((img,i)=>{let src=img.getAttribute('data-src')||img.getAttribute('data-original')||img.currentSrc||img.src||'';try{src=new URL(src,location.href).href}catch(e){src=''}if(!/^https:\\/\\//i.test(src)||seen.has(src))return;seen.add(src);let p=family==='toki'?Number(img.getAttribute('data-page')||img.getAttribute('data-page-number')||(img.alt||'').match(/(?:page|페이지)\\s*(\\d+)/i)?.[1]):i+1;if(!Number.isInteger(p)||p<1)p=i+1;rows.push({page:p,urls:[src]})});" +
            "const countEl=document.querySelector('[data-viewer-image-count]');expected=family==='toki'?Number(countEl&&(countEl.getAttribute('data-viewer-image-count')||countEl.dataset.viewerImageCount)||0):rows.length;}" +
            "if(document.body)window.scrollTo(0,Math.min(document.body.scrollHeight,window.scrollY+Math.max(innerHeight,1200)));" +
            "return JSON.stringify({pending:false,expected:Number.isFinite(expected)?expected:0,pages:rows,allImages:document.images.length,viewport:innerHeight,documentHeight:document.documentElement.scrollHeight,href:location.href});})()";
        String raw = evaluateText(webView, script, 5_000L);
        try {
            JSONObject json = new JSONObject(raw);
            String error = json.optString("error", "");
            if (!error.isBlank()) return Snapshot.error(error);
            if (json.optBoolean("pending")) return Snapshot.pendingSnapshot();
            String text = json.optString("text", "");
            if (!text.isBlank()) return Snapshot.novel(text, json.optString("title", ""), json);
            int expected = json.optInt("expected", 0);
            JSONArray values = json.optJSONArray("pages");
            Map<Integer, List<String>> unique = new LinkedHashMap<>();
            if (values != null) {
                for (int index = 0; index < values.length(); index++) {
                    JSONObject row = values.optJSONObject(index);
                    if (row == null) continue;
                    int number = row.optInt("page", index + 1);
                    JSONArray urls = row.optJSONArray("urls");
                    LinkedHashSet<String> candidates = new LinkedHashSet<>();
                    if (urls != null) {
                        for (int candidate = 0; candidate < urls.length(); candidate++) {
                            String imageUrl = urls.optString(candidate, "");
                            if (imageUrl.startsWith("https://")) candidates.add(imageUrl);
                        }
                    }
                    if (number > 0 && !candidates.isEmpty()) unique.putIfAbsent(number, List.copyOf(candidates));
                }
            }
            List<Page> pages = new ArrayList<>();
            int outputNumber = 1;
            for (List<String> urls : unique.values()) pages.add(new Page(outputNumber++, urls));
            return new Snapshot(false, "", "", "", expected, pages, json.optInt("allImages"),
                json.optInt("viewport"), json.optInt("documentHeight"), json.optString("href"));
        } catch (Exception error) {
            return null;
        }
    }

    private String evaluateText(WebView webView, String script, long timeoutMs) throws EngineException {
        AtomicReference<String> raw = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> webView.evaluateJavascript(script, value -> {
            raw.set(value);
            done.countDown();
        }));
        await(done, timeoutMs, "javascript_timeout");
        try {
            Object decoded = new JSONTokener(raw.get()).nextValue();
            return decoded instanceof String text ? text : String.valueOf(decoded);
        } catch (Exception error) {
            throw new EngineException("javascript_result_invalid", "WebView 응답 형식이 올바르지 않습니다.");
        }
    }

    private void dispatchShortGesture(Session session) throws EngineException {
        runOnMain(() -> {
            int width = Math.max(1, session.webView.getWidth());
            int height = Math.max(1, session.webView.getHeight());
            float x = width * 0.5f;
            float startY = height * 0.72f;
            float endY = Math.max(1f, startY - Math.max(72f, height * 0.05f));
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0);
            MotionEvent move = MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_MOVE, x, endY, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 130L, MotionEvent.ACTION_UP, x, endY, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                session.webView.dispatchTouchEvent(down);
                session.webView.dispatchTouchEvent(move);
                session.webView.dispatchTouchEvent(up);
                Log.i(TAG, "virtual touch dispatched viewport=" + width + "x" + height);
            } finally {
                down.recycle();
                move.recycle();
                up.recycle();
            }
        });
    }

    private void destroy(Session session) {
        if (!sessions.remove(session)) return;
        main.post(session.display::close);
    }

    private void runOnMain(Runnable runnable) throws EngineException {
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> { try { runnable.run(); } finally { done.countDown(); } });
        await(done, 5_000, "main_thread_timeout");
    }

    private static void await(CountDownLatch latch, long timeoutMs, String code) throws EngineException {
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new EngineException(code, "WebView 응답 시간이 초과되었습니다.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted", "작업이 중단되었습니다.");
        }
    }

    private static void sleep(long millis) throws EngineException {
        try { Thread.sleep(millis); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted", "작업이 중단되었습니다.");
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        closed = true;
        synchronized (warmLock) {
            warmSessions.clear();
        }
        List<Session> open;
        synchronized (sessions) { open = new ArrayList<>(sessions); }
        open.forEach(this::destroy);
    }

    static final class EngineException extends Exception {
        final String code;
        EngineException(String code, String message) { super(message); this.code = code; }
    }

    static final class Page {
        final int number;
        final List<String> urls;
        Page(int number, List<String> urls) { this.number = number; this.urls = List.copyOf(urls); }
        @Override public String toString() { return number + ":" + urls; }
    }

    static final class Result {
        final String kind;
        final List<Page> pages;
        final String referer;
        final String userAgent;
        final boolean pending;
        final String text;
        final String title;

        private Result(String kind, List<Page> pages, String referer, String userAgent, boolean pending, String text, String title) {
            this.kind = kind;
            this.pages = List.copyOf(pages);
            this.referer = referer;
            this.userAgent = userAgent;
            this.pending = pending;
            this.text = text;
            this.title = title;
        }

        static Result images(List<Page> pages, String referer, String userAgent, boolean pending) {
            return new Result("images", pages, referer, userAgent, pending, "", "");
        }

        static Result novel(String text, String title) {
            return new Result("novel", List.of(), "", "", false, text, title);
        }
    }

    private record Snapshot(boolean pending, String error, String text, String title, int expected, List<Page> pages,
                            int allImages, int viewport, int documentHeight, String href) {
        static Snapshot pendingSnapshot() { return new Snapshot(true, "", "", "", 0, List.of(), 0, 0, 0, ""); }
        static Snapshot error(String code) { return new Snapshot(false, code, "", "", 0, List.of(), 0, 0, 0, ""); }
        static Snapshot novel(String text, String title, JSONObject json) {
            return new Snapshot(false, "", text, title, 0, List.of(), json.optInt("allImages"), json.optInt("viewport"), json.optInt("documentHeight"), json.optString("href"));
        }
    }

    private static final class Session {
        final VirtualWebViewDisplay display;
        final WebView webView;
        final String userAgent;
        volatile boolean pageFinished;
        volatile boolean gestureSent;
        volatile String lastMainError = "사이트 응답을 확인하지 못했습니다.";
        Session(VirtualWebViewDisplay display, String userAgent) {
            this.display = display;
            this.webView = display.webView;
            this.userAgent = userAgent;
        }
    }

}
