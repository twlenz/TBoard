package dev.tboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Setup steps and every setting. Opens from the app icon or by long-pressing the comma key. */
public final class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status, learned;
    private Button enableButton, switchButton;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("TBoard", 28, true);
        root.addView(title);
        root.addView(text("A keyboard that works entirely on this phone. The app has no internet "
                + "permission, so nothing you type can leave the device. Everything it learns is "
                + "stored in the app's private storage and excluded from cloud backup.", 14, false));

        section("Setup");
        status = text("", 15, false);
        root.addView(status);
        enableButton = button("1. Turn on TBoard in keyboard settings",
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        switchButton = button("2. Switch to TBoard", v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        EditText test = new EditText(this);
        test.setHint("Try it here");
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        test.setMinLines(2);
        root.addView(test, matchWrap(dp(8)));
        root.addView(text("Tip: long-press the space bar to switch keyboards, and long-press the comma "
                + "key for this settings screen.", 13, false));

        section("Appearance");
        choice("Theme", Prefs.THEME, Prefs.theme(prefs),
                new String[]{"system", "light", "dark", "black"},
                new String[]{"Follow system", "Light", "Dark", "Black (OLED)"});
        choice("Keyboard height", Prefs.HEIGHT, Prefs.height(prefs),
                new String[]{"short", "normal", "tall"},
                new String[]{"Short", "Normal", "Tall"});

        String current = null;
        for (Prefs.Toggle t : Prefs.TOGGLES) {
            if (!t.section.equals(current)) {
                section(t.section);
                current = t.section;
            }
            toggle(t);
        }

        section("What it has learned");
        learned = text("", 15, false);
        root.addView(learned);
        button("Clear everything learned", v -> new AlertDialog.Builder(this)
                .setTitle("Clear learned data?")
                .setMessage("This forgets the words you added, your word pairs and your tap habits. "
                        + "The built-in dictionary stays.")
                .setPositiveButton("Clear", (d, w) -> {
                    Engine.get(this).clearUserData();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show());

        section("About");
        root.addView(text("English word frequencies come from the wordfreq project (data licensed "
                + "CC BY-SA 4.0, by Robyn Speer), filtered through the Hunspell en_US spelling "
                + "dictionary (SCOWL). Offensive-word list from better_profanity (MIT). Emoji names and search "
                + "keywords from Unicode emoji data and CLDR (Unicode License v3).", 13, false));

        Engine.get(this).whenReady(this::refresh);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh(); // the keyboard picker closes without pausing us
    }

    private void refresh() {
        boolean enabled = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (getPackageName().equals(info.getPackageName())) enabled = true;
            }
        }
        String def = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = def != null && def.startsWith(getPackageName() + "/");
        if (status != null) {
            status.setText(selected ? "✅ TBoard is your current keyboard."
                    : enabled ? "Step 1 done. Now switch to TBoard."
                    : "TBoard is installed but not turned on yet.");
            enableButton.setEnabled(!enabled);
            switchButton.setEnabled(enabled && !selected);
        }
        if (learned != null) {
            int n = Engine.get(this).learnedWordCount();
            learned.setText(n < 0 ? "Loading…" : n + " words and names remembered from your typing.");
        }
    }

    // ------------------------------------------------------------------ builders

    private void section(String name) {
        TextView t = text(name, 13, true);
        t.setAllCaps(true);
        t.setPadding(0, dp(28), 0, dp(6));
        t.setAlpha(0.7f);
        root.addView(t);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b, matchWrap(dp(6)));
        return b;
    }

    private void toggle(Prefs.Toggle t) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        Switch sw = new Switch(this);
        sw.setText(t.title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(prefs.getBoolean(t.key, t.def));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(t.key, checked).apply());
        box.addView(sw);
        TextView sum = text(t.summary, 13, false);
        sum.setAlpha(0.75f);
        box.addView(sum);
        root.addView(box);
    }

    private void choice(String label, String key, String value, String[] values, String[] labels) {
        root.addView(text(label, 16, false));
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < values.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(labels[i]);
            r.setId(View.generateViewId());
            r.setTag(values[i]);
            g.addView(r);
            if (values[i].equals(value)) r.setChecked(true);
        }
        g.setOnCheckedChangeListener((group, id) -> {
            View v = group.findViewById(id);
            if (v != null) prefs.edit().putString(key, (String) v.getTag()).apply();
        });
        root.addView(g);
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.gravity = Gravity.START;
        return lp;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
