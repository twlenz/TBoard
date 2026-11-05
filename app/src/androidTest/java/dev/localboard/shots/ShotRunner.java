package dev.localboard.shots;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Drives the real keyboard on an emulator with injected touches and saves screenshots
 * to /sdcard/lbshots. Not a unit test: run by tools/ci_screenshots.sh in CI so the
 * keyboard can be seen and checked without a phone. Uses only platform APIs.
 *
 *   adb shell am instrument -w -r dev.localboard.test/dev.localboard.shots.ShotRunner
 */
public class ShotRunner extends Instrumentation {
    private static final String TAG = "LBSHOTS";
    private static final String DIR = "/sdcard/lbshots";

    private UiAutomation ui;
    private Activity activity;
    private EditText field;
    private SharedPreferences prefs;
    private final StringBuilder errors = new StringBuilder();

    // keyboard view geometry on screen, in px (from the LBGEOM log line)
    private float vx, vy, vw, unit, rowH, stripH;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle out = new Bundle();
        try {
            run();
            out.putString("errors", errors.length() == 0 ? "none" : errors.toString());
            finish(Activity.RESULT_OK, out);
        } catch (Throwable t) {
            out.putString("error", Log.getStackTraceString(t));
            finish(Activity.RESULT_CANCELED, out);
        }
    }

    private void run() throws Exception {
        ui = getUiAutomation();
        sh("mkdir -p " + DIR);
        prefs = getTargetContext().getSharedPreferences("settings", 0);
        prefs.edit().clear().putString("theme", "light").commit();

        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setClassName(getTargetContext(), "dev.localboard.SettingsActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = startActivitySync(i);
        waitForIdleSync();
        sleep(1500);

        step("01_settings_top", () -> shot("01_settings_top"));
        step("02_settings_middle", () -> {
            scrollTo(0.35f);
            shot("02_settings_middle");
        });
        step("03_settings_bottom", () -> {
            scrollTo(1f);
            shot("03_settings_bottom");
            scrollTo(0f);
        });

        // open the keyboard on the "Try it here" field with a real tap
        runOnMainSync(() -> field = findEdit(activity.getWindow().getDecorView()));
        sh("logcat -c");
        int[] loc = new int[2];
        runOnMainSync(() -> field.getLocationOnScreen(loc));
        tap(loc[0] + field.getWidth() / 2f, loc[1] + field.getHeight() / 2f);
        readGeometry();
        sleep(800);

        step("04_keyboard_light", () -> shot("04_keyboard_light"));
        step("05_typing_teh", () -> {
            type("teh");
            shot("05_typing_teh");
        });
        step("06_typing_brpwn", () -> {
            type(" quick brpwn");
            shot("06_typing_brpwn");
            type(" fox ");
        });
        step("07_swipe_trail", () -> {
            sleep(400);
            swipe("jumps", "07_swipe_trail");
            sleep(300);
            shot("08_swipe_result");
        });
        step("09_longpress_e", () -> {
            sleep(400);
            float[] p = key('e');
            long d = SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN, d, p[0], p[1]);
            sleep(800);
            shot("09_longpress_e");
            touch(MotionEvent.ACTION_CANCEL, d, p[0], p[1]);
            sleep(300);
        });
        step("10_key_preview", () -> {
            float[] p = key('g');
            long d = SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN, d, p[0], p[1]);
            sleep(200);
            shot("10_key_preview");
            touch(MotionEvent.ACTION_UP, d, p[0], p[1]);
            sleep(200);
            tapSpecial(9.25f, 2); // delete the g
        });
        step("11_symbols", () -> {
            tapSpecial(0.75f, 3);
            shot("11_symbols");
            tapSpecial(0.75f, 2);
            shot("12_symbols2");
            tapSpecial(0.75f, 3); // ABC (bottom-left on the second symbols page)
        });
        step("13_emoji", () -> {
            tapSpecial(3f, 3);
            sleep(600);
            shot("13_emoji");
            // the ABC button is at the bottom-left of the emoji panel
            tap(vx + 30 * density(), vy + rowsBottom() - 23 * density());
            sleep(400);
        });
        step("14_dark", () -> {
            prefs.edit().putString("theme", "dark").commit();
            sleep(600);
            shot("14_dark");
            prefs.edit().putString("theme", "black").commit();
            sleep(600);
            shot("15_black");
            prefs.edit().putString("theme", "light").commit();
        });
        step("16_tall", () -> {
            sh("logcat -c");
            prefs.edit().putString("height", "tall").commit();
            sleep(800);
            readGeometry();
            shot("16_tall");
            sh("logcat -c");
            prefs.edit().putString("height", "short").commit();
            sleep(800);
            readGeometry();
            shot("17_short");
            sh("logcat -c");
            prefs.edit().putString("height", "normal").commit();
            sleep(800);
            readGeometry();
        });
        step("18_next_word", () -> {
            clearField();
            type("see you soon. see you soon. see you soon. see you ");
            sleep(300);
            shot("18_next_word");
        });
        step("19_numbers_hidden", () -> {
            prefs.edit().putBoolean("number_hints", false).commit();
            sleep(500);
            shot("19_no_number_hints");
            prefs.edit().putBoolean("number_hints", true).commit();
        });
        step("20_landscape", () -> {
            sh("settings put system accelerometer_rotation 0");
            sh("settings put system user_rotation 1");
            sleep(2500);
            shot("20_landscape");
            sh("settings put system user_rotation 0");
            sleep(1500);
        });
    }

    // ------------------------------------------------------------------ helpers

    private interface Step {
        void run() throws Exception;
    }

    private void step(String name, Step s) {
        try {
            s.run();
        } catch (Throwable t) {
            errors.append(name).append(": ").append(t).append('\n');
            Log.e(TAG, name, t);
        }
    }

    private float density() {
        return getTargetContext().getResources().getDisplayMetrics().density;
    }

    private float rowsBottom() {
        return stripH + 4 * rowH;
    }

    private void readGeometry() throws Exception {
        for (int tries = 0; tries < 40; tries++) {
            String log = sh("logcat -d -s LBGEOM:I");
            String last = null;
            for (String line : log.split("\n")) {
                int k = line.indexOf("LBGEOM");
                if (k >= 0 && line.indexOf(':', k) > 0) last = line.substring(line.indexOf(':', k) + 1).trim();
            }
            if (last != null) {
                String[] p = last.split(" ");
                if (p.length >= 7) {
                    vx = Float.parseFloat(p[0]);
                    vy = Float.parseFloat(p[1]);
                    vw = Float.parseFloat(p[2]);
                    unit = Float.parseFloat(p[4]);
                    rowH = Float.parseFloat(p[5]);
                    stripH = Float.parseFloat(p[6]);
                    if (vy > 0) return;
                }
            }
            sleep(250);
        }
        throw new IllegalStateException("keyboard never reported its position (is it the current keyboard?)");
    }

    private static final String[] ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final float[] OFFSET = {0.5f, 1f, 2f};

    private float[] key(char c) {
        for (int r = 0; r < 3; r++) {
            int i = ROWS[r].indexOf(c);
            if (i >= 0) return at(OFFSET[r] + i, r);
        }
        throw new IllegalArgumentException("no key " + c);
    }

    private float[] at(float unitX, int row) {
        return new float[]{vx + unitX * unit, vy + stripH + (row + 0.5f) * rowH};
    }

    private void tapSpecial(float unitX, int row) {
        float[] p = at(unitX, row);
        tap(p[0], p[1]);
        sleep(250);
    }

    private void type(String s) {
        for (char c : s.toCharArray()) {
            float[] p;
            if (c == ' ') p = at(5.5f, 3);
            else if (c == '.') p = at(8f, 3);
            else if (c == ',') p = at(2f, 3);
            else p = key(Character.toLowerCase(c));
            tap(p[0], p[1]);
        }
        sleep(300);
    }

    /** Swipes through the word's keys; if shotName is set, takes a screenshot before lifting. */
    private void swipe(String word, String shotName) throws Exception {
        long d = SystemClock.uptimeMillis();
        float[] p = key(word.charAt(0));
        touch(MotionEvent.ACTION_DOWN, d, p[0], p[1]);
        for (int i = 1; i < word.length(); i++) {
            float[] q = key(word.charAt(i));
            for (int s = 1; s <= 8; s++) {
                touch(MotionEvent.ACTION_MOVE, d, p[0] + (q[0] - p[0]) * s / 8f, p[1] + (q[1] - p[1]) * s / 8f);
                sleep(12);
            }
            p = q;
        }
        if (shotName != null) shot(shotName);
        touch(MotionEvent.ACTION_UP, d, p[0], p[1]);
    }

    private void tap(float x, float y) {
        long d = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, d, x, y);
        sleep(40);
        touch(MotionEvent.ACTION_UP, d, x, y);
        sleep(140);
    }

    private void touch(int action, long downTime, float x, float y) {
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        ui.injectInputEvent(e, true);
        e.recycle();
    }

    private void shot(String name) throws Exception {
        sh("screencap -p " + DIR + "/" + name + ".png");
    }

    private void clearField() {
        runOnMainSync(() -> field.setText(""));
        sleep(400);
    }

    private void scrollTo(float fraction) {
        runOnMainSync(() -> {
            ScrollView sv = (ScrollView) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            int max = Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight());
            sv.scrollTo(0, (int) (max * fraction));
        });
        sleep(500);
    }

    private static EditText findEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText e = findEdit(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    private String sh(String cmd) throws Exception {
        ParcelFileDescriptor pfd = ui.executeShellCommand(cmd);
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        } finally {
            pfd.close();
        }
        return sb.toString();
    }

    private static void sleep(long ms) {
        SystemClock.sleep(ms);
    }
}
