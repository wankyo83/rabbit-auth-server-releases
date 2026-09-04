package com.rabbit.authserver.androidtest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class AuthServerService extends Service {
    static final String ACTION_START = "com.rabbit.authserver.androidtest.START";
    static final String ACTION_STOP = "com.rabbit.authserver.androidtest.STOP";
    private static final String CHANNEL_ID = "rabbit_auth_server_test";
    private static final int NOTIFICATION_ID = 9898;

    private LocalHttpServer httpServer;
    private JobManager jobManager;
    private WebAuthEngine webAuthEngine;
    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("시작 중 · 127.0.0.1:9898", false));
        if (httpServer == null) {
            try {
                webAuthEngine = new WebAuthEngine(this, this::manualAuthRequired);
                jobManager = new JobManager(this, webAuthEngine);
                httpServer = new LocalHttpServer(9898, AppState.serverKey(this), jobManager);
                httpServer.start();
                running = true;
                AppState.update(this, true, "실행 중 · 127.0.0.1:9898 · 동시 작업 " + AppConfig.CONCURRENCY + "개");
                updateNotification("실행 중 · Mihon 요청 대기", false);
            } catch (Exception error) {
                AppState.update(this, false, "시작 실패 · " + safeMessage(error));
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void manualAuthRequired(String url) {
        AppState.job(this, url, "사용자 확인 필요 · 인증 화면을 열어 주세요");
        updateNotification("인증 화면에서 사이트 확인이 필요합니다", true);
    }

    void updateJobNotification(String text) {
        updateNotification(text, false);
    }

    private void updateNotification(String text, boolean manualAction) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(text, manualAction));
    }

    private Notification notification(String text, boolean manualAction) {
        Intent mainIntent = manualAction
            ? new Intent(this, ManualAuthActivity.class).putExtra("url", AppState.prefs(this).getString(AppState.KEY_LAST_URL, ""))
            : new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            manualAction ? 2 : 1,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, AuthServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        builder.setSmallIcon(com.rabbit.authserver.androidtest.R.drawable.ic_rabbit_server)
            .setContentTitle("Rabbit 인증 서버 (테스트)")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(!manualAction)
            .addAction(new Notification.Action.Builder(null, "서버 중지", stopPendingIntent).build());
        if (manualAction) {
            builder.addAction(new Notification.Action.Builder(null, "인증 화면 열기", contentIntent).build());
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Rabbit 인증 서버",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("같은 기기의 Mihon 요청을 처리하는 동안 표시됩니다.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        running = false;
        if (httpServer != null) httpServer.close();
        if (jobManager != null) jobManager.close();
        if (webAuthEngine != null) webAuthEngine.close();
        httpServer = null;
        jobManager = null;
        webAuthEngine = null;
        AppState.update(this, false, "중지됨");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
