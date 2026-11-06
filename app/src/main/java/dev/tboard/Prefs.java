package dev.tboard;

import android.content.Context;
import android.content.SharedPreferences;

/** All settings, with their labels and defaults, in one place. */
public final class Prefs {
    public static final String FILE = "settings";

    public static final String THEME = "theme";        // system | light | dark | black
    public static final String HEIGHT = "height";      // short | normal | tall
    public static final String DATA_GENERATION = "data_generation";

    public static final String AUTOCORRECT = "autocorrect";
    public static final String UNDO_AUTOCORRECT = "undo_autocorrect";
    public static final String DOUBLE_SPACE_PERIOD = "double_space_period";
    public static final String AUTO_CAPITALIZE = "auto_capitalize";
    public static final String PUNCTUATION_SWAP = "punctuation_swap";
    public static final String SWIPE = "swipe";
    public static final String SWIPE_TRAIL = "swipe_trail";
    public static final String AUTO_SPACE = "auto_space";
    public static final String SUGGESTIONS = "suggestions";
    public static final String NEXT_WORD = "next_word";
    public static final String SUGGEST_EVERYWHERE = "suggest_everywhere";
    public static final String LEARN = "learn";
    public static final String BLOCK_OFFENSIVE = "block_offensive";
    public static final String SPACE_CURSOR = "space_cursor";
    public static final String NUMBER_HINTS = "number_hints";
    public static final String KEY_PREVIEW = "key_preview";
    public static final String VIBRATE = "vibrate";
    public static final String SOUND = "sound";

    public static final class Toggle {
        public final String key, title, summary, section;
        public final boolean def;

        Toggle(String section, String key, String title, String summary, boolean def) {
            this.section = section;
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.def = def;
        }
    }

    public static final Toggle[] TOGGLES = {
            new Toggle("Corrections", AUTOCORRECT, "Autocorrect",
                    "Fix misspelled words when you press space or punctuation", true),
            new Toggle("Corrections", UNDO_AUTOCORRECT, "Backspace undoes autocorrect",
                    "Backspace right after a correction puts back exactly what you typed", true),
            new Toggle("Corrections", BLOCK_OFFENSIVE, "Block offensive words",
                    "Never suggest or autocorrect to offensive words (words you type yourself are still learned)", false),
            new Toggle("Typing", DOUBLE_SPACE_PERIOD, "Double-space for period",
                    "Tapping space twice after a word types \". \" (not after a backspace)", false),
            new Toggle("Typing", AUTO_CAPITALIZE, "Auto-capitalize",
                    "Capitalize the first letter of each sentence", true),
            new Toggle("Typing", PUNCTUATION_SWAP, "Smart punctuation spacing",
                    "Typing . , ? ! right after a word's space moves the space after the punctuation", true),
            new Toggle("Typing", SPACE_CURSOR, "Slide space bar to move cursor",
                    "Drag left or right on the space bar to move the text cursor", true),
            new Toggle("Swipe", SWIPE, "Swipe typing",
                    "Slide across letters to type a word", true),
            new Toggle("Swipe", SWIPE_TRAIL, "Show swipe trail",
                    "Draw the path of your finger while swiping", true),
            new Toggle("Swipe", AUTO_SPACE, "Space between swiped words",
                    "Add a space automatically before the next word after a swipe", true),
            new Toggle("Suggestions", SUGGESTIONS, "Suggestion bar",
                    "Show word suggestions above the keys", true),
            new Toggle("Suggestions", SUGGEST_EVERYWHERE, "Suggestions in every app",
                    "Some apps, like Google Keep, ask keyboards not to suggest or correct words. Gboard ignores that, "
                            + "and so does TBoard while this is on. Browser text boxes that ask are still respected.", true),
            new Toggle("Suggestions", NEXT_WORD, "Next-word suggestions",
                    "After a word, suggest the words you usually type next", true),
            new Toggle("Suggestions", LEARN, "Learn from my typing",
                    "Remember new words, word pairs, misspellings you fix by hand, and where you tap each key. Stored only on this phone. Never learns in password or incognito fields.", true),
            new Toggle("Keys", NUMBER_HINTS, "Number hints",
                    "Show numbers on the top row; long-press a letter to type its number or accents", true),
            new Toggle("Keys", KEY_PREVIEW, "Key preview popup",
                    "Show an enlarged letter above your finger when tapping", true),
            new Toggle("Keys", VIBRATE, "Vibrate on keypress",
                    "Uses your phone's touch-vibration setting", false),
            new Toggle("Keys", SOUND, "Sound on keypress", "Click sound on each key", false),
    };

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean on(SharedPreferences p, String key) {
        for (Toggle t : TOGGLES) {
            if (t.key.equals(key)) return p.getBoolean(key, t.def);
        }
        return p.getBoolean(key, false);
    }

    public static String theme(SharedPreferences p) {
        return p.getString(THEME, "system");
    }

    public static String height(SharedPreferences p) {
        return p.getString(HEIGHT, "normal");
    }

    private Prefs() {
    }
}
