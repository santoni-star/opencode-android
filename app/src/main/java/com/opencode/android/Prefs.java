package com.opencode.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

/** Runtime preferences that must remain private to this Android app. */
public final class Prefs {
    private static final String FILE = "opencode-runtime";
    private static final String KEY_COMPAT = "compat";
    private static final String KEY_WEB_PASSWORD = "web_password";

    public static final int WEB_PORT = 4096;

    private final SharedPreferences preferences;

    public Prefs(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean compatMode() {
        return preferences.getBoolean(KEY_COMPAT, false);
    }

    public void compatMode(boolean enabled) {
        preferences.edit().putBoolean(KEY_COMPAT, enabled).apply();
    }

    /** A random, app-private password used only for the loopback web server. */
    public String webPassword() {
        String value = preferences.getString(KEY_WEB_PASSWORD, "");
        if (value != null && !value.isEmpty()) return value;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        value = Base64.encodeToString(secret, Base64.NO_WRAP);
        preferences.edit().putString(KEY_WEB_PASSWORD, value).apply();
        return value;
    }
}
