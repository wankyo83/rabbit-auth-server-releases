package com.rabbit.authserver.androidtest;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

final class AppState {
    static final String PREFS = "rabbit_android_auth_test";
    static final String KEY_SERVER_KEY = "server_key";
    static final String KEY_RUNNING = "running";
    static final String KEY_STATUS = "status";
    static final String KEY_LAST_URL = "last_url";
    static final String KEY_LAST_JOB = "last_job";
    static final String DEFAULT_STATUS = "중지됨";

    private AppState() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String serverKey(Context context) {
        SharedPreferences preferences = prefs(context);
        String existing = preferences.getString(KEY_SERVER_KEY, "");
        if (validServerKey(existing)) return existing;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        preferences.edit().putString(KEY_SERVER_KEY, generated).apply();
        return generated;
    }

    static boolean validServerKey(String value) {
        return value != null && value.length() >= 4 && value.length() <= 128 && value.trim().equals(value) &&
            value.chars().allMatch(character -> character >= 33 && character <= 126);
    }

    static boolean setServerKey(Context context, String value) {
        if (!validServerKey(value)) return false;
        prefs(context).edit().putString(KEY_SERVER_KEY, value).apply();
        return true;
    }

    static void update(Context context, boolean running, String status) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).putString(KEY_STATUS, status).apply();
    }

    static void job(Context context, String url, String summary) {
        prefs(context).edit().putString(KEY_LAST_URL, url == null ? "" : url)
            .putString(KEY_LAST_JOB, summary == null ? "" : summary).apply();
    }
}
