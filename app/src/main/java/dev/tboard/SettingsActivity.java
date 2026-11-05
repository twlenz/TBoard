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
import android.view.inputmethod.EditorInfo;
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

import java.util.List;

/** Setup steps and every setting. Opens from the app icon or by long-pressing the comma key. */
public final class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status, learned;
    private LinearLayout keptList;
    private EditText keepInput;
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

        section("Words to keep");
        root.addView(text("TBoard never autocorrects these and suggests them as you type: names, slang, "
                + "words it keeps changing. Words you kept by undoing an autocorrection are here too.", 13, false));
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        keepInput = new EditText(this);
        keepInput.setHint("Add a word");
        keepInput.setSingleLine(true);
        keepInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keepInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        keepInput.setOnEditorActionListener((v, actionId, event) -> {
            addKeptWords();
            return true;
        });
        addRow.addView(keepInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button add = new Button(this);
        add.setText("Add");
        add.setAllCaps(false);
        add.setOnClickListener(v -> addKeptWords());
        addRow.addView(add, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(addRow, matchWrap(dp(4)));
        keptList = new LinearLayout(this);
        keptList.setOrientation(LinearLayout.VERTICAL);
        root.addView(keptList, matchWrap(dp(4)));

        section("What it has learned");
        learned = text("", 15, false);
        root.addView(learned);
        button("Clear everything learned", v -> new AlertDialog.Builder(this)
                .setTitle("Clear learned data?")
                .setMessage("This forgets the words and word pairs TBoard learned, the fixes you taught it "
                        + "and your tap habits. Your \"Words to keep\" list and the built-in dictionary stay.")
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
        refreshKept();
    }

    /** Adds the word(s) typed in the box (several can be separated by spaces or commas). */
    private void addKeptWords() {
        String typed = keepInput.getText().toString();
        Engine.get(this).whenReady(() -> {
            for (String raw : typed.split("[\\s,;]+")) {
                String w = cleanWord(raw);
                if (w != null) Engine.get(this).keepWord(w);
            }
            keepInput.setText("");
            refresh();
        });
    }

    /** A word without surrounding punctuation, or null if it isn't a single word. */
    public static String cleanWord(String raw) {
        String w = raw.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
        if (w.isEmpty() || w.length() > 40 || !w.matches("[\\p{L}\\p{N}'\u2019-]+") || !w.matches(".*\\p{L}.*")) return null;
        return w;
    }

    private void refreshKept() {
        if (keptList == null) return;
        keptList.removeAllViews();
        List<String> words = Engine.get(this).keptWords();
        if (words == null) {
            keptList.addView(text("Loading…", 14, false));
            return;
        }
        if (words.isEmpty()) {
            TextView none = text("No words yet.", 14, false);
            none.setAlpha(0.7f);
            keptList.addView(none);
            return;
        }
        for (String w : words) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(w, 16, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = new Button(this);
            remove.setText("Remove");
            remove.setAllCaps(false);
            remove.setOnClickListener(v -> {
                Engine.get(this).unkeepWord(w);
                refresh();
            });
            row.addView(remove, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            keptList.addView(row);
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
