import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.*;
import android.text.InputType;
import android.content.ContextWrapper;
import dev.localboard.*;
import dev.localboard.engine.*;
import dev.localboard.engine.Dictionary;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * End-to-end typing tests: drives the real LocalKeyboardService against a fake text field.
 * Compiled against the Android stand-ins from android_stubs.py (no Android SDK needed).
 * Usage: see run_tests.sh.
 */
public class FlowTest {
    // A text field: text, cursor, composing region.
    static class Field implements InputConnection {
        StringBuilder t = new StringBuilder(); int cur = 0, cs = -1, ce = -1; int action = -1;
        public CharSequence getTextBeforeCursor(int n, int f) { return t.substring(Math.max(0, cur - n), cur); }
        public CharSequence getSelectedText(int f) { return null; }
        public boolean finishComposingText() { cs = ce = -1; return true; }
        public boolean setComposingText(CharSequence s, int p) { int a = cs >= 0 ? cs : cur, b = cs >= 0 ? ce : cur; t.replace(a, b, s.toString()); cs = a; ce = a + s.length(); cur = ce; if (s.length()==0) cs=ce=-1; return true; }
        public boolean commitText(CharSequence s, int p) { int a = cs >= 0 ? cs : cur, b = cs >= 0 ? ce : cur; t.replace(a, b, s.toString()); cur = a + s.length(); cs = ce = -1; return true; }
        public boolean deleteSurroundingText(int b, int a) { int s = Math.max(0, cur - b); t.delete(s, cur); cur = s; return true; }
        public boolean performEditorAction(int a) { action = a; return true; }
        public int getCursorCapsMode(int m) { String b = t.substring(0, cur).replaceAll("\\s+$", ""); return (b.isEmpty() || (cur > 0 && t.charAt(cur-1)==' ' && ".!?".indexOf(b.charAt(b.length()-1)) >= 0)) ? m : 0; }
        public String toString() { return t.toString().replace("\n", "\\n") + (cs >= 0 ? "  [composing " + t.substring(cs, ce) + "]" : ""); }
    }
    static Field f; static LocalKeyboardService s; static KeyGeometry geo = KeyGeometry.qwerty(1.5f); static Random rnd = new Random(7);
    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        Dictionary d = Dictionary.load(new FileInputStream(a[0]));
        UserModel u = new UserModel(); Lexicon lex = new Lexicon(d, u);
        s = new LocalKeyboardService();
        s.onCreate();
        set("lex", lex); set("tap", new TapDecoder(lex, geo)); set("swipe", new SwipeDecoder(lex, geo));
        s.onCreateInputView();
        InputMethodService.testKeys = k -> { if (k == 67 && f.cur > 0) { f.t.deleteCharAt(f.cur - 1); f.cur--; } else if (k == 21) f.cur = Math.max(0, f.cur-1); else if (k == 22) f.cur = Math.min(f.t.length(), f.cur+1); };

        start(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0);
        type("teh "); check("autocorrect teh -> The", "The ");
        key(-5); check("backspace undoes autocorrect", "Teh ");
        type("teh "); check("reverted word is not corrected again", "Teh teh ");

        start(InputType.TYPE_CLASS_TEXT, 0);
        type("i think its fine  "); check("i->I, double space period", "I think its fine. ");
        type("dont go "); check("dont -> don't, capital after period", "I think its fine. Don't go ");
        type(","); check("smart punctuation moves the space", "I think its fine. Don't go, ");
        type("thsi"); check("composing word stays editable", "I think its fine. Don't go, thsi  [composing thsi]");
        type("."); check("autocorrect on punctuation", "I think its fine. Don't go, this.");

        start(InputType.TYPE_CLASS_TEXT, 0);
        swipe("hello"); swipe("world"); check("swiped words get spaces between them", "Hello world  [composing world]");
        type("."); check("punctuation after swipe has no space", "Hello world.");
        swipe("how"); check("swipe after period gets a space", "Hello world. How  [composing How]");
        key(-5); check("backspace deletes a whole swiped word", "Hello world. ");
        swipe("are"); type("you"); check("tapping after a swipe starts a new word", "Hello world. Are you  [composing you]");

        start(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0);
        type("teh "); check("no autocorrect in password fields", "teh ");

        type("paris!  ab  "); check("password: no recasing, no double-space period", "teh paris!  ab  ");
        start(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0);
        type("john@mail"); type("."); type("com"); check("email field keeps lowercase names", "john@mail.com  [composing com]");

        // a short drag that decodes to no word must still type the key it started on
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("hel");
        boolean took = s.onSwipe(new float[]{geo.x('l'), geo.x('l') + 0.3f}, new float[]{geo.y('l'), geo.y('l')}, 2);
        check2("tiny swipe is declined so the view can type it as a tap", !took);
        check("declined swipe leaves the word alone", "Hel  [composing Hel]");

        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND);
        type("ok"); key(-6); check("enter sends in a send field", "Ok"); check2("editor action SEND", f.action == EditorInfo.IME_ACTION_SEND);

        ContextWrapper.PREFS.put(Prefs.UNDO_AUTOCORRECT, false);
        ContextWrapper.PREFS.put(Prefs.DOUBLE_SPACE_PERIOD, false);
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("wjat "); key(-5); check("undo-autocorrect setting off: backspace just deletes", "What");
        type("  "); check("double-space setting off", "What  ");
        ContextWrapper.PREFS.put(Prefs.AUTOCORRECT, false);
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("teh "); check("autocorrect setting off", "Teh ");
        ContextWrapper.PREFS.clear();

        // learning: a new word typed a few times is kept, and a frequent pair becomes a prediction
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("zorbly "); type("zorbly "); type("zorbly ");
        check("new word is learned instead of corrected", "Zorbly zorbly zorbly ");
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("see you tomorrow see you tomorrow see you ");
        String[] strip = (String[]) getField(get("kv"), "suggestions");
        check2("next-word prediction after 'you' offers 'tomorrow' (strip: " + Arrays.toString(strip) + ")", Arrays.asList(strip).contains("tomorrow"));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1); // the keyboard's background I/O thread would otherwise keep the JVM alive
    }

    static void start(int inputType, int action) throws Exception {
        f = new Field(); InputMethodService.testIC = f;
        EditorInfo e = new EditorInfo(); e.inputType = inputType; e.imeOptions = action;
        s.onStartInputView(e, false);
    }
    static void type(String str) {
        for (char c : str.toCharArray()) {
            if (c == ' ') s.onKey(' ', Float.NaN, Float.NaN);
            else if (c >= 'a' && c <= 'z') s.onKey(c, geo.x(c) + (float) rnd.nextGaussian() * 0.15f, geo.y(c) + (float) rnd.nextGaussian() * 0.15f);
            else s.onKey(c, Float.NaN, Float.NaN);
            sel();
        }
    }
    static void key(int code) { s.onKey(code, Float.NaN, Float.NaN); sel(); }
    static void sel() { s.onUpdateSelection(0, 0, f.cur, f.cur, f.cs, f.ce); }
    static void swipe(String w) {
        ArrayList<Float> xs = new ArrayList<>(), ys = new ArrayList<>(); char prev = 0;
        for (char c : w.toCharArray()) { if (c == prev) continue; prev = c;
            if (!xs.isEmpty()) { float x0 = xs.get(xs.size()-1), y0 = ys.get(ys.size()-1); for (int i = 1; i < 8; i++) { xs.add(x0 + (geo.x(c)-x0)*i/8f); ys.add(y0 + (geo.y(c)-y0)*i/8f); } }
            xs.add(geo.x(c)); ys.add(geo.y(c)); }
        float[] ax = new float[xs.size()], ay = new float[xs.size()];
        for (int i = 0; i < ax.length; i++) { ax[i] = xs.get(i); ay[i] = ys.get(i); }
        s.onSwipe(ax, ay, ax.length); sel();
    }
    static void check(String what, String want) { check2(what + "  -> got \"" + f + "\"", f.toString().equals(want)); }
    static void check2(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "PASS  " : "FAIL  ") + what); }
    static void set(String name, Object v) throws Exception { Field2.set(s, name, v); }
    static Object get(String name) throws Exception { return getField(s, name); }
    static Object getField(Object o, String name) throws Exception { java.lang.reflect.Field fl = o.getClass().getDeclaredField(name); fl.setAccessible(true); return fl.get(o); }
    static class Field2 { static void set(Object o, String n, Object v) throws Exception { java.lang.reflect.Field fl = o.getClass().getDeclaredField(n); fl.setAccessible(true); fl.set(o, v); } }
}
