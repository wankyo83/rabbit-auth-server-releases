package com.rabbit.authserver.androidtest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdater implements AutoCloseable {
    interface Listener {
        void onChecking();
        void onUpToDate();
        void onUpdateAvailable(ReleaseInfo release);
        void onDownloadStarted(ReleaseInfo release);
        void onDownloadReady(ReleaseInfo release);
        void onError(String message);
    }

    static final class ReleaseInfo {
        final String version;
        final int versionCode;
        final String fileName;
        final String downloadUrl;
        final String sha256;

        ReleaseInfo(String version, int versionCode, String fileName, String downloadUrl, String sha256) {
            this.version = version;
            this.versionCode = versionCode;
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
            this.sha256 = sha256.toUpperCase(Locale.ROOT);
        }
    }

    private static final String PREFS = "android_app_updates";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_VERSION = "version";
    private static final String KEY_VERSION_CODE = "version_code";
    private static final String KEY_FILE_NAME = "file_name";
    private static final String KEY_DOWNLOAD_URL = "download_url";
    private static final String KEY_SHA256 = "sha256";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DownloadManager downloads;
    private final SharedPreferences preferences;
    private boolean receiverRegistered;
    private boolean checking;
    private boolean verifying;
    private boolean waitingForInstallPermission;
    private boolean closed;
    private long readyDownloadId = -1L;
    private ReleaseInfo readyRelease;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (completedId == preferences.getLong(KEY_DOWNLOAD_ID, -1L)) inspectPersistedDownload(true);
        }
    };

    AppUpdater(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    void start() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }
        receiverRegistered = true;
        if (!inspectPersistedDownload(false)) check(false);
    }

    void onResume() {
        if (waitingForInstallPermission && activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = false;
            launchInstaller();
        }
    }

    boolean hasReadyDownload() {
        return readyDownloadId >= 0L && readyRelease != null;
    }

    void check(boolean userInitiated) {
        if (closed || checking) return;
        checking = true;
        listener.onChecking();
        io.execute(() -> {
            try {
                ReleaseInfo release = fetchRelease();
                post(() -> {
                    checking = false;
                    if (release.versionCode > BuildConfig.VERSION_CODE) listener.onUpdateAvailable(release);
                    else listener.onUpToDate();
                });
            } catch (Exception error) {
                post(() -> {
                    checking = false;
                    listener.onError(userInitiated
                        ? "업데이트 확인 실패 · " + safeMessage(error)
                        : "자동 업데이트 확인 실패 · 아래 버튼으로 다시 확인하세요");
                });
            }
        });
    }

    void download(ReleaseInfo release) {
        if (closed) return;
        try {
            validateRelease(release);
            File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) throw new IllegalStateException("다운로드 폴더를 열 수 없습니다.");
            File previous = new File(directory, release.fileName);
            if (previous.exists() && !previous.delete()) throw new IllegalStateException("이전 다운로드 파일을 지울 수 없습니다.");

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.downloadUrl))
                .setTitle("Rabbit 인증 서버 " + release.version)
                .setDescription("업데이트 APK 다운로드")
                .setMimeType(APK_MIME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, release.fileName);
            long id = downloads.enqueue(request);
            preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_VERSION, release.version)
                .putInt(KEY_VERSION_CODE, release.versionCode)
                .putString(KEY_FILE_NAME, release.fileName)
                .putString(KEY_DOWNLOAD_URL, release.downloadUrl)
                .putString(KEY_SHA256, release.sha256)
                .apply();
            readyDownloadId = -1L;
            readyRelease = null;
            listener.onDownloadStarted(release);
        } catch (Exception error) {
            listener.onError("업데이트 다운로드 실패 · " + safeMessage(error));
        }
    }

    void installReady() {
        if (!hasReadyDownload()) {
            listener.onError("설치할 업데이트 파일이 없습니다.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = true;
            Intent settings = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(settings);
            return;
        }
        launchInstaller();
    }

    private ReleaseInfo fetchRelease() throws Exception {
        URL url = URI.create(AppConfig.UPDATE_MANIFEST_URL).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "RabbitAuthAndroid/" + BuildConfig.VERSION_NAME);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
            byte[] bytes = readLimited(connection.getInputStream(), MAX_MANIFEST_BYTES);
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            ReleaseInfo release = new ReleaseInfo(
                json.optString("version", ""),
                json.optInt("versionCode", -1),
                json.optString("fileName", ""),
                json.optString("downloadUrl", ""),
                json.optString("sha256", "")
            );
            validateRelease(release);
            return release;
        } finally {
            connection.disconnect();
        }
    }

    private boolean inspectPersistedDownload(boolean installWhenReady) {
        long id = preferences.getLong(KEY_DOWNLOAD_ID, -1L);
        ReleaseInfo release = persistedRelease();
        if (id < 0L || release == null) return false;
        if (release.versionCode <= BuildConfig.VERSION_CODE) {
            clearPersistedDownload();
            return false;
        }
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (!cursor.moveToFirst()) {
                clearPersistedDownload();
                return false;
            }
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (state == DownloadManager.STATUS_SUCCESSFUL) {
                verifyDownloadedApk(id, release, installWhenReady);
            } else if (state == DownloadManager.STATUS_FAILED) {
                clearPersistedDownload();
                listener.onError("업데이트 다운로드가 완료되지 않았습니다.");
            } else {
                listener.onDownloadStarted(release);
            }
            return true;
        } catch (Exception error) {
            clearPersistedDownload();
            listener.onError("다운로드 상태 확인 실패 · " + safeMessage(error));
            return false;
        }
    }

    private void verifyDownloadedApk(long id, ReleaseInfo release, boolean installWhenReady) {
        if (verifying || closed) return;
        verifying = true;
        io.execute(() -> {
            try {
                Uri uri = downloads.getUriForDownloadedFile(id);
                if (uri == null) throw new IllegalStateException("다운로드 파일을 찾을 수 없습니다.");
                String actual;
                try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("다운로드 파일을 열 수 없습니다.");
                    actual = sha256(input);
                }
                if (!actual.equalsIgnoreCase(release.sha256)) throw new SecurityException("APK SHA-256 불일치");
                post(() -> {
                    verifying = false;
                    readyDownloadId = id;
                    readyRelease = release;
                    listener.onDownloadReady(release);
                    if (installWhenReady) installReady();
                });
            } catch (Exception error) {
                post(() -> {
                    verifying = false;
                    clearPersistedDownload();
                    listener.onError("업데이트 검증 실패 · " + safeMessage(error));
                });
            }
        });
    }

    private void launchInstaller() {
        try {
            Uri uri = downloads.getUriForDownloadedFile(readyDownloadId);
            if (uri == null) throw new IllegalStateException("다운로드 파일을 찾을 수 없습니다.");
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Exception error) {
            listener.onError("설치 화면을 열지 못했습니다 · " + safeMessage(error));
        }
    }

    private ReleaseInfo persistedRelease() {
        try {
            ReleaseInfo release = new ReleaseInfo(
                preferences.getString(KEY_VERSION, ""),
                preferences.getInt(KEY_VERSION_CODE, -1),
                preferences.getString(KEY_FILE_NAME, ""),
                preferences.getString(KEY_DOWNLOAD_URL, ""),
                preferences.getString(KEY_SHA256, "")
            );
            validateRelease(release);
            return release;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearPersistedDownload() {
        preferences.edit().clear().apply();
        readyDownloadId = -1L;
        readyRelease = null;
    }

    static void validateRelease(ReleaseInfo release) {
        if (release.version.isBlank() || release.version.length() > 48 || release.versionCode < 1) {
            throw new IllegalArgumentException("업데이트 버전 정보가 올바르지 않습니다.");
        }
        if (!release.fileName.matches("RabbitAuthServer-Android-[A-Za-z0-9._-]+\\.apk")) {
            throw new IllegalArgumentException("업데이트 파일 이름이 올바르지 않습니다.");
        }
        if (!release.sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("업데이트 해시가 올바르지 않습니다.");
        }
        URI uri = URI.create(release.downloadUrl);
        String expectedPrefix = "/wankyo83/rabbit-auth-server-releases/releases/download/";
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) ||
            uri.getUserInfo() != null || uri.getFragment() != null || !uri.getPath().startsWith(expectedPrefix) ||
            !uri.getPath().endsWith("/" + release.fileName)) {
            throw new IllegalArgumentException("업데이트 다운로드 주소가 올바르지 않습니다.");
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IllegalStateException("업데이트 정보가 너무 큽니다.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format(Locale.ROOT, "%02X", item));
        return value.toString();
    }

    private void post(Runnable action) {
        main.post(() -> { if (!closed) action.run(); });
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        closed = true;
        if (receiverRegistered) {
            try { activity.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        io.shutdownNow();
    }
}
