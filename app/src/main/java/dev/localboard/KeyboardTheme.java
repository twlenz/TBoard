package dev.localboard;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/** Colors for the keyboard. Light, dark and black (for OLED screens), plus "follow system". */
final class KeyboardTheme {
    int background, key, keyPressed, special, specialPressed, text, textDim, accent, accentText,
            stripText, divider, popup, popupSelected, shadow;
    boolean dark;

    static KeyboardTheme resolve(Context ctx, String pref) {
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String mode = pref;
        if ("system".equals(mode)) mode = night ? "dark" : "light";
        KeyboardTheme t = new KeyboardTheme();
        if ("light".equals(mode)) {
            t.dark = false;
            t.background = 0xFFE8EAED;
            t.key = 0xFFFFFFFF;
            t.keyPressed = 0xFFD2D5DA;
            t.special = 0xFFC9CDD3;
            t.specialPressed = 0xFFB4B9C0;
            t.text = 0xFF1F2023;
            t.textDim = 0xFF6B6F76;
            t.stripText = 0xFF1F2023;
            t.divider = 0x33000000;
            t.popup = 0xFFFFFFFF;
            t.shadow = 0x33000000;
        } else {
            boolean black = "black".equals(mode);
            t.dark = true;
            t.background = black ? 0xFF000000 : 0xFF1F2124;
            t.key = black ? 0xFF1A1A1A : 0xFF3A3C40;
            t.keyPressed = black ? 0xFF333333 : 0xFF55585D;
            t.special = black ? 0xFF111111 : 0xFF2B2D31;
            t.specialPressed = black ? 0xFF2A2A2A : 0xFF45484D;
            t.text = 0xFFEDEEF0;
            t.textDim = 0xFF9AA0A6;
            t.stripText = 0xFFEDEEF0;
            t.divider = 0x33FFFFFF;
            t.popup = black ? 0xFF262626 : 0xFF4A4D52;
            t.shadow = 0x66000000;
        }
        t.accent = accentColor(ctx, t.dark);
        t.accentText = t.dark ? 0xFF0B1220 : 0xFFFFFFFF;
        t.popupSelected = t.accent;
        return t;
    }

    /** Uses the phone's wallpaper-based accent color on Android 12+, otherwise a blue. */
    private static int accentColor(Context ctx, boolean dark) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return ctx.getColor(dark ? android.R.color.system_accent1_300
                        : android.R.color.system_accent1_600);
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return dark ? 0xFF8AB4F8 : 0xFF1A73E8;
    }
}
