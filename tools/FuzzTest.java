import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.*;
import android.text.InputType;
import android.content.ContextWrapper;
import dev.tboard.*;
import dev.tboard.engine.*;
import dev.tboard.engine.Dictionary;
import java.io.*;
import java.util.*;

/**
 * Random-action stress test for the keyboard service, against a fake text field that supports a
 * cursor anywhere and selections. After every action it checks:
 *  - nothing threw;
 *  - the field is consistent (cursor, selection and composing region inside the text);
 *  - text far from the cursor never changed (a keyboard must only edit near where you type);
 *  - nothing was learned in password or incognito fields.
 * Usage: java FuzzTest DICT [actions-per-field] [seed]
 */
public class FuzzTest {
    /** A text field: text, cursor (selection start), selection end, composing region. */
    static class Field implements InputConnection {
        StringBuilder t = new StringBuilder(); int cur = 0, selEnd = 0, cs = -1, ce = -1; int actions = 0;
        int lo() { return cur; } int hi() { return Math.max(cur, selEnd); }
        public CharSequence getTextBeforeCursor(int n, int f) { return t.substring(Math.max(0, lo() - n), lo()); }
        public CharSequence getTextAfterCursor(int n, int f) { return t.substring(hi(), Math.min(t.length(), hi() + n)); }
        public CharSequence getSelectedText(int f) { return hi() > lo() ? t.substring(lo(), hi()) : null; }
        public boolean beginBatchEdit() { return true; } public boolean endBatchEdit() { return true; }
        public boolean finishComposingText() { cs = ce = -1; return true; }
        // like Android's BaseInputConnection: replace the composing region, or else the selection
        public boolean setComposingText(CharSequence s, int p) {
            int a = cs >= 0 ? cs : lo(), b = cs >= 0 ? ce : hi();
            t.replace(a, b, s.toString()); cs = a; ce = a + s.length(); cur = selEnd = ce;
            if (s.length() == 0) cs = ce = -1; return true; }
        public boolean commitText(CharSequence s, int p) {
            int a = cs >= 0 ? cs : lo(), b = cs >= 0 ? ce : hi();
            t.replace(a, b, s.toString()); cur = selEnd = a + s.length(); cs = ce = -1; return true; }
        public boolean deleteSurroundingText(int before, int after) {
            int e = hi(), ae = Math.min(t.length(), e + after); t.delete(e, ae);
            int s = Math.max(0, lo() - before); int n = lo() - s; t.delete(s, lo());
            cur -= n; selEnd -= n;
            if (cs >= 0) { cs = Math.max(0, Math.min(cs, t.length())); ce = Math.max(cs, Math.min(ce, t.length())); }
            return true; }
        public boolean performEditorAction(int a) { actions++; return true; }
        public int getCursorCapsMode(int m) { String b = t.substring(0, lo()).replaceAll("\\s+$", ""); return (b.isEmpty() || (lo() > 0 && t.charAt(lo()-1)==' ' && ".!?".indexOf(b.charAt(b.length()-1)) >= 0)) ? m : 0; }
        void key(int k) { // what an app does with a key event
            if (k == 67) { if (hi() > lo()) commitText("", 1); else if (lo() > 0) { int n = lo() > 1 && Character.isLowSurrogate(t.charAt(lo()-1)) ? 2 : 1; cs = ce = -1; t.delete(lo() - n, lo()); cur -= n; selEnd = cur; } }
            else if (k == 21) { cs = ce = -1; cur = selEnd = Math.max(0, lo() - 1); }
            else if (k == 22) { cs = ce = -1; cur = selEnd = Math.min(t.length(), hi() + 1); }
        }
        String check() {
            if (cur < 0 || cur > t.length() || selEnd < cur || selEnd > t.length()) return "cursor out of range " + cur + "/" + selEnd + " len " + t.length();
            if (cs >= 0 && (cs > ce || ce > t.length())) return "composing out of range " + cs + "-" + ce;
            return null;
        }
    }

    static Field f; static TBoardService s; static KeyGeometry geo = KeyGeometry.qwerty(1.5f);
    static Random rnd; static Lexicon lex;
    static final String WORDS = "the quick brown fox jumps over lazy dog hello world teh thier recieve becuase going "
            + "dont cant im youre a i to be or not hate love what want this that there where when";

    public static void main(String[] a) throws Exception {
        Dictionary d = Dictionary.load(new FileInputStream(a[0]));
        int perField = a.length > 1 ? Integer.parseInt(a[1]) : 4000;
        long seed = a.length > 2 ? Long.parseLong(a[2]) : 12345;
        rnd = new Random(seed);
        lex = new Lexicon(d, new UserModel());
        s = new TBoardService();
        s.onCreate();
        set("lex", lex); set("tap", new TapDecoder(lex, geo)); set("swipe", new SwipeDecoder(lex, geo));
        s.onCreateInputView();
        InputMethodService.testKeys = k -> f.key(k);

        int[][] fields = {
                {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0},
                {InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND},
                {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0},
                {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0},
                {InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING},
                {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0},
                {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 0},
                {InputType.TYPE_CLASS_NUMBER, 0},
                {InputType.TYPE_NULL, 0},
                {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0},
        };
        String[] toggles = {Prefs.DOUBLE_SPACE_PERIOD, Prefs.AUTOCORRECT, Prefs.UNDO_AUTOCORRECT, Prefs.AUTO_SPACE,
                Prefs.PUNCTUATION_SWAP, Prefs.SUGGESTIONS, Prefs.LEARN, Prefs.BLOCK_OFFENSIVE, Prefs.AUTO_CAPITALIZE};
        int failures = 0, total = 0;
        for (int round = 0; round < 3; round++) {
            for (int[] fd : fields) {
                ContextWrapper.PREFS.clear();
                for (String tg : toggles) if (rnd.nextInt(4) == 0) ContextWrapper.PREFS.put(tg, rnd.nextBoolean());
                f = new Field(); InputMethodService.testIC = f;
                // start with some existing text, as when editing a message
                String seedText = randomText(200);
                f.t.append(seedText); f.cur = f.selEnd = rnd.nextInt(f.t.length() + 1);
                EditorInfo e = new EditorInfo(); e.inputType = fd[0]; e.imeOptions = fd[1];
                s.onStartInputView(e, false);
                boolean noLearning = (fd[0] & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || (fd[0] & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || (fd[1] & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                        || Boolean.FALSE.equals(ContextWrapper.PREFS.get(Prefs.LEARN));
                String modelBefore = lex.user.serialize();
                for (int i = 0; i < perField; i++) {
                    total++;
                    String before = f.t.toString(); int curBefore = f.cur, selBefore = f.selEnd;
                    String what = "";
                    try {
                        what = act();
                        s.onUpdateSelection(0, 0, f.cur, f.selEnd, f.cs, f.ce);
                    } catch (Throwable ex) {
                        failures++;
                        System.out.println("CRASH field " + Arrays.toString(fd) + " action " + what + ": " + ex);
                        ex.printStackTrace(System.out);
                        if (failures > 5) System.exit(1);
                        continue;
                    }
                    String bad = f.check();
                    // everything more than 64 characters before the cursor must be untouched
                    int keep = curBefore - 64; // measured from where the cursor was, not where it ended up
                    if (bad == null && !what.startsWith("move") && keep > 0
                            && !f.t.toString().startsWith(before.substring(0, keep))) {
                        bad = "text far from the cursor changed";
                    }
                    if (bad != null) {
                        failures++;
                        System.out.println("FAIL field " + Arrays.toString(fd) + " after " + what + ": " + bad
                                + "\n  before: " + clip(before, curBefore) + "\n  after:  " + clip(f.t.toString(), f.cur));
                        if (failures > 5) System.exit(1);
                        f.cs = f.ce = -1; f.cur = f.selEnd = Math.min(f.cur, f.t.length());
                    }
                }
                s.onFinishInputView(true); s.onFinishInput();
                if (noLearning && !lex.user.serialize().equals(modelBefore)) {
                    failures++;
                    System.out.println("FAIL learned something in a no-learning field " + Arrays.toString(fd) + " prefs " + ContextWrapper.PREFS);
                }
            }
        }
        System.out.println(total + " random actions, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String act() {
        int r = rnd.nextInt(100);
        if (r < 45) { char c = (char) ('a' + rnd.nextInt(26)); if (rnd.nextInt(10) == 0) c = Character.toUpperCase(c); tap(c); return "letter " + c; }
        if (r < 55) { s.onKey(' ', Float.NaN, Float.NaN); return "space"; }
        if (r < 67) { s.onKey(-5, Float.NaN, Float.NaN); return "backspace"; }
        if (r < 70) { char c = ".,?!'-:;()@#\"0123456789".charAt(rnd.nextInt(23)); s.onKey(c, Float.NaN, Float.NaN); return "char " + c; }
        if (r < 72) { s.onKey(-6, Float.NaN, Float.NaN); return "enter"; }
        if (r < 76) { String[] w = WORDS.split(" "); swipe(w[rnd.nextInt(w.length)]); return "swipe"; }
        if (r < 80) { s.onSuggestionPicked(rnd.nextInt(3)); return "suggestion"; }
        if (r < 85) { f.cs = f.ce = -1; f.cur = f.selEnd = rnd.nextInt(f.t.length() + 1); return "move cursor"; }
        if (r < 87) { f.cs = f.ce = -1; int x = rnd.nextInt(f.t.length() + 1), y = rnd.nextInt(f.t.length() + 1); f.cur = Math.min(x, y); f.selEnd = Math.max(x, y); return "move select"; }
        if (r < 89) { s.onCursorMove(rnd.nextInt(7) - 3); return "space-bar slide"; }
        if (r < 90) { s.onKey(-1, Float.NaN, Float.NaN); return "shift"; }
        if (r < 92) { s.onKey(-7, Float.NaN, Float.NaN); if (rnd.nextBoolean()) s.onEmoji("😀"); s.onEmojiBack(); return "emoji"; }
        if (r < 94) { s.onEmojiSearch(); for (int i = rnd.nextInt(5); i > 0; i--) tap((char) ('a' + rnd.nextInt(26))); if (rnd.nextBoolean()) s.onSearchPick("❤️"); s.onKey(rnd.nextBoolean() ? -6 : -5, Float.NaN, Float.NaN); s.onSearchClose(); return "emoji search"; }
        if (r < 96) { s.onText(rnd.nextBoolean() ? "é" : ".com"); return "long-press text"; }
        if (r < 97) { s.onKey(rnd.nextBoolean() ? -2 : -4, Float.NaN, Float.NaN); return "layout"; }
        if (r < 98) { android.content.ClipboardManager.testClip = rnd.nextBoolean() ? "pasted words" : null; s.onPaste(); return "paste"; }
        String[] keys = {Prefs.AUTOCORRECT, Prefs.DOUBLE_SPACE_PERIOD, Prefs.SUGGESTIONS, Prefs.SWIPE};
        ContextWrapper.PREFS.put(keys[rnd.nextInt(keys.length)], rnd.nextBoolean());
        return "setting";
    }

    static void tap(char c) {
        char k = Character.toLowerCase(c);
        s.onKey(k, geo.x(k) + (float) rnd.nextGaussian() * 0.3f, geo.y(k) + (float) rnd.nextGaussian() * 0.3f);
    }

    static void swipe(String w) {
        ArrayList<Float> xs = new ArrayList<>(), ys = new ArrayList<>(); char prev = 0;
        for (char c : w.toCharArray()) { if (c == prev || !geo.has(c)) continue; prev = c;
            if (!xs.isEmpty()) { float x0 = xs.get(xs.size()-1), y0 = ys.get(ys.size()-1); for (int i = 1; i < 8; i++) { xs.add(x0 + (geo.x(c)-x0)*i/8f + (float) rnd.nextGaussian() * 0.1f); ys.add(y0 + (geo.y(c)-y0)*i/8f); } }
            xs.add(geo.x(c)); ys.add(geo.y(c)); }
        if (xs.size() < 2) return;
        float[] ax = new float[xs.size()], ay = new float[xs.size()];
        for (int i = 0; i < ax.length; i++) { ax[i] = xs.get(i); ay[i] = ys.get(i); }
        s.onSwipe(ax, ay, ax.length);
    }

    static String randomText(int n) {
        String[] w = WORDS.split(" "); StringBuilder sb = new StringBuilder();
        while (sb.length() < n) { sb.append(w[rnd.nextInt(w.length)]); sb.append(rnd.nextInt(8) == 0 ? ". " : " "); }
        return sb.toString();
    }

    static String clip(String t, int cur) {
        int a = Math.max(0, cur - 40), b = Math.min(t.length(), cur + 20);
        return "…" + t.substring(a, cur).replace("\n", "\\n") + "|" + t.substring(cur, b).replace("\n", "\\n") + "…";
    }

    static void set(String name, Object v) throws Exception {
        java.lang.reflect.Field fl = s.getClass().getDeclaredField(name); fl.setAccessible(true); fl.set(s, v);
    }
}
