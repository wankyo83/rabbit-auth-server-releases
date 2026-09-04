package com.rabbit.authserver.androidtest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class ManualAuthActivity extends Activity {
    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra("url");
        if (!SiteRules.allowedForManualView(url)) {
            Toast.makeText(this, "열 수 있는 지원 사이트 회차 주소가 없습니다", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        Button close = button("닫기");
        TextView title = new TextView(this);
        title.setText("사이트 인증 · 완료 후 Mihon에서 새로고침");
        title.setTextSize(15);
        title.setTextColor(Color.rgb(16, 34, 61));
        title.setPadding(dp(10), 0, dp(10), 0);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button reload = button("새로고침");
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(74), dp(48)));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(94), dp(48)));
        root.addView(toolbar);

        webView = new WebView(this);
        WebAuthEngine.configure(webView);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String loadedUrl) {
                CookieManager.getInstance().flush();
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        close.setOnClickListener(v -> finish());
        reload.setOnClickListener(v -> webView.reload());
        webView.loadUrl(url);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        return button;
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
