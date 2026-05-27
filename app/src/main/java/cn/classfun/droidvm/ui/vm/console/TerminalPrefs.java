package cn.classfun.droidvm.ui.vm.console;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Persists user preferences for the in-app terminal (VMConsole / Agent operation).
 *
 * <p>Currently only the font size adjusted by pinch-zoom is stored, so that the value
 * is preserved across navigations and process restarts.</p>
 */
public final class TerminalPrefs {
    private static final String PREFS_NAME = "droidvm_prefs";
    private static final String KEY_TERMINAL_FONT_SIZE = "terminal_font_size";

    public static final int DEFAULT_FONT_SIZE = 14;
    public static final int MIN_FONT_SIZE = 8;
    public static final int MAX_FONT_SIZE = 32;

    private TerminalPrefs() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getFontSize(@NonNull Context context) {
        int size = prefs(context).getInt(KEY_TERMINAL_FONT_SIZE, DEFAULT_FONT_SIZE);
        if (size < MIN_FONT_SIZE) size = MIN_FONT_SIZE;
        if (size > MAX_FONT_SIZE) size = MAX_FONT_SIZE;
        return size;
    }

    public static void setFontSize(@NonNull Context context, int size) {
        if (size < MIN_FONT_SIZE) size = MIN_FONT_SIZE;
        if (size > MAX_FONT_SIZE) size = MAX_FONT_SIZE;
        prefs(context).edit().putInt(KEY_TERMINAL_FONT_SIZE, size).apply();
    }
}
