package com.rabbit.authserver.androidtest;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusValue;
    private TextView lastJobValue;
    private Button startButton;
    private Button stopButton;
    private EditText keyInput;
    private Button saveKeyButton;
    private TextView updateStatusValue;
    private Button updateButton;
    private AppUpdater updater;
    private AppUpdater.ReleaseInfo availableUpdate;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        setContentView(buildUi());
        updater = new AppUpdater(this, new AppUpdater.Listener() {
            @Override public void onChecking() {
                availableUpdate = null;
                updateStatusValue.setText("GitHub에서 최신 버전 확인 중…");
                updateButton.setText("확인 중…");
                updateButton.setEnabled(false);
            }

            @Override public void onUpToDate() {
                availableUpdate = null;
                updateStatusValue.setText("현재 " + BuildConfig.VERSION_NAME + " · 최신 버전입니다");
                updateButton.setText("업데이트 확인");
                updateButton.setEnabled(true);
            }

            @Override public void onUpdateAvailable(AppUpdater.ReleaseInfo release) {
                availableUpdate = release;
                updateStatusValue.setText("새 버전 " + release.version + " 사용 가능");
                updateButton.setText(release.version + " 다운로드");
                updateButton.setEnabled(true);
            }

            @Override public void onDownloadStarted(AppUpdater.ReleaseInfo release) {
                availableUpdate = null;
                updateStatusValue.setText(release.version + " 다운로드 중 · 완료되면 APK를 검증합니다");
                updateButton.setText("다운로드 중…");
                updateButton.setEnabled(false);
            }

            @Override public void onDownloadReady(AppUpdater.ReleaseInfo release) {
                availableUpdate = null;
                updateStatusValue.setText(release.version + " 다운로드 및 SHA-256 검증 완료");
                updateButton.setText("업데이트 설치");
                updateButton.setEnabled(true);
            }

            @Override public void onError(String message) {
                availableUpdate = null;
                updateStatusValue.setText(message);
                updateButton.setText(updater != null && updater.hasReadyDownload() ? "업데이트 설치" : "업데이트 확인");
                updateButton.setEnabled(true);
            }
        });
        updater.start();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refreshTask);
        if (updater != null) updater.onResume();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (updater != null) updater.close();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 247, 251));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, dp(32));
        scroll.addView(root);

        TextView title = text("Rabbit 인증 서버", 28, Color.rgb(16, 34, 61));
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("Android " + BuildConfig.VERSION_NAME, 15, Color.rgb(75, 94, 124));
        subtitle.setPadding(0, dp(6), 0, dp(24));
        root.addView(subtitle);

        root.addView(label("서버 상태"));
        statusValue = value("중지됨");
        root.addView(statusValue);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, dp(22));
        startButton = button("서버 시작");
        stopButton = button("서버 중지");
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        stopParams.setMarginStart(dp(10));
        actions.addView(stopButton, stopParams);
        root.addView(actions);

        startButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, AuthServerService.class).setAction(AuthServerService.ACTION_START);
            startForegroundService(intent);
            Toast.makeText(this, "서버를 시작합니다", Toast.LENGTH_SHORT).show();
        });
        stopButton.setOnClickListener(v -> startService(new Intent(this, AuthServerService.class).setAction(AuthServerService.ACTION_STOP)));

        root.addView(label("인식된 디바이스 화면"));
        root.addView(value(deviceDisplaySummary()));
        TextView displayNote = text(
            "인증 작업에서는 이 화면 크기와 밀도로 보이지 않는 VirtualDisplay를 만들고 WebView를 정상 렌더링합니다.",
            14,
            Color.rgb(75, 94, 124)
        );
        displayNote.setPadding(0, dp(7), 0, dp(18));
        root.addView(displayNote);

        root.addView(label("Mihon에 입력할 서버 주소"));
        TextView address = value("http://127.0.0.1:9898");
        root.addView(address);
        Button copyAddress = outlineButton("주소 복사");
        copyAddress.setOnClickListener(v -> copy("서버 주소", address.getText().toString()));
        root.addView(copyAddress);

        root.addView(spacer(18));
        root.addView(label("접속 키"));
        keyInput = new EditText(this);
        keyInput.setText(AppState.serverKey(this));
        keyInput.setTextSize(17);
        keyInput.setTextColor(Color.rgb(16, 34, 61));
        keyInput.setBackgroundColor(Color.WHITE);
        keyInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(keyInput);
        Button copyKey = outlineButton("접속 키 복사");
        copyKey.setOnClickListener(v -> copy("접속 키", keyInput.getText().toString()));
        root.addView(copyKey);
        saveKeyButton = outlineButton("접속 키 저장");
        saveKeyButton.setOnClickListener(v -> {
            String value = keyInput.getText().toString();
            if (AppState.prefs(this).getBoolean(AppState.KEY_RUNNING, false)) {
                Toast.makeText(this, "서버를 중지한 뒤 접속 키를 변경하세요", Toast.LENGTH_LONG).show();
            } else if (AppState.setServerKey(this, value)) {
                Toast.makeText(this, "접속 키를 저장했습니다", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "공백 없는 영문·숫자·기호 4~128자로 입력하세요", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(saveKeyButton);

        root.addView(spacer(18));
        root.addView(label("최근 작업"));
        lastJobValue = value("아직 요청이 없습니다");
        root.addView(lastJobValue);
        Button openAuth = outlineButton("인증 화면 열기");
        openAuth.setOnClickListener(v -> {
            String url = AppState.prefs(this).getString(AppState.KEY_LAST_URL, "");
            if (url == null || url.isBlank()) {
                Toast.makeText(this, "먼저 Mihon에서 회차를 요청하세요", Toast.LENGTH_SHORT).show();
            } else {
                startActivity(new Intent(this, ManualAuthActivity.class).putExtra("url", url));
            }
        });
        root.addView(openAuth);

        root.addView(spacer(18));
        root.addView(label("앱 업데이트"));
        updateStatusValue = value("GitHub에서 최신 버전을 확인합니다");
        root.addView(updateStatusValue);
        updateButton = outlineButton("업데이트 확인");
        updateButton.setOnClickListener(v -> {
            if (updater == null) return;
            if (updater.hasReadyDownload()) updater.installReady();
            else if (availableUpdate != null) updater.download(availableUpdate);
            else updater.check(true);
        });
        root.addView(updateButton);
        TextView updateNote = text(
            "새 버전이 있을 때만 알립니다. APK를 내려받아 SHA-256을 확인한 뒤 Android 설치 화면을 엽니다. 실제 설치는 사용자가 승인해야 합니다.",
            14,
            Color.rgb(75, 94, 124)
        );
        updateNote.setLineSpacing(0, 1.2f);
        updateNote.setPadding(0, dp(8), 0, 0);
        root.addView(updateNote);

        TextView note = text(
            "테스트 범위: Newtoki/토끼/SBXH, Blacktoon, Jjaptoon, 11toon, WFWF, Goodtoon, xtoon, 네이버 웹툰\n" +
                "서버를 먼저 시작한 뒤 Mihon 확장앱에서 외부 인증 서버를 켜고 위 주소와 접속 키를 입력하세요. " +
                "소설토끼 본문도 지원합니다. 자동 인증이 멈추면 인증 화면을 열어 사이트 확인을 완료한 후 Mihon에서 새로고침하세요. " +
                "화면이 꺼지면 Android 절전 정책에 따라 서버가 중단될 수 있습니다.",
            14,
            Color.rgb(75, 94, 124)
        );
        note.setLineSpacing(0, 1.25f);
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void refresh() {
        var preferences = AppState.prefs(this);
        boolean running = AuthServerService.isRunning();
        if (!running && preferences.getBoolean(AppState.KEY_RUNNING, false)) {
            AppState.update(this, false, "중지됨");
            preferences = AppState.prefs(this);
        }
        String status = preferences.getString(AppState.KEY_STATUS, AppState.DEFAULT_STATUS);
        String job = preferences.getString(AppState.KEY_LAST_JOB, "");
        statusValue.setText(status == null ? AppState.DEFAULT_STATUS : status);
        statusValue.setTextColor(running ? Color.rgb(0, 128, 83) : Color.rgb(198, 40, 40));
        lastJobValue.setText(job == null || job.isBlank() ? "아직 요청이 없습니다" : job);
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        keyInput.setEnabled(!running);
        saveKeyButton.setEnabled(!running);
    }

    private TextView label(String value) {
        TextView view = text(value, 15, Color.rgb(47, 65, 91));
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(7));
        return view;
    }

    private TextView value(String value) {
        TextView view = text(value, 17, Color.rgb(16, 34, 61));
        view.setBackgroundColor(Color.WHITE);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setAllCaps(false);
        return button;
    }

    private Button outlineButton(String value) {
        Button button = button(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        params.setMargins(0, dp(8), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private View spacer(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return view;
    }

    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + "를 복사했습니다", Toast.LENGTH_SHORT).show();
    }

    private String deviceDisplaySummary() {
        try {
            return VirtualWebViewDisplay.readDeviceGeometry(this).summary();
        } catch (Exception error) {
            return "화면 정보를 확인할 수 없습니다";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
