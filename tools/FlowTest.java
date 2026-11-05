import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.*;
import android.text.InputType;
import android.content.ContextWrapper;
import dev.tboard.*;
import dev.tboard.engine.*;
import dev.tboard.engine.Dictionary;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * End-to-end typing tests: drives the real TBoardService against a fake text field.
 * Compiled against the Android stand-ins from android_stubs.py (no Android SDK needed).
 * Usage: see run_tests.sh.
 */
public class FlowTest {
    // A text field: text, cursor, composing region.
    static class Field implements InputConnection {
        StringBuilder t = new StringBuilder(); int cur = 0, cs = -1, ce = -1; int action = -1;
        public CharSequence getTextBeforeCursor(int n, int f) { return t.substring(Math.max(0, cur - n), cur); }
        public CharSequence getTextAfterCursor(int n, int f) { return t.substring(cur, Math.min(t.length(), cur + n)); }
        public boolean beginBatchEdit() { return true; } public boolean endBatchEdit() { return true; }
        public CharSequence getSelectedText(int f) { return null; }
        public boolean finishComposingText() { cs = ce = -1; return true; }
        public boolean setComposingText(CharSequence s, int p) { int a = cs >= 0 ? cs : cur, b = cs >= 0 ? ce : cur; t.replace(a, b, s.toString()); cs = a; ce = a + s.length(); cur = ce; if (s.length()==0) cs=ce=-1; return true; }
        public boolean commitText(CharSequence s, int p) { int a = cs >= 0 ? cs : cur, b = cs >= 0 ? ce : cur; t.replace(a, b, s.toString()); cur = a + s.length(); cs = ce = -1; return true; }
        public boolean deleteSurroundingText(int b, int a) { int s = Math.max(0, cur - b); t.delete(s, cur); cur = s; return true; }
        public boolean performEditorAction(int a) { action = a; return true; }
        public int getCursorCapsMode(int m) { String b = t.substring(0, cur).replaceAll("\\s+$", ""); return (b.isEmpty() || (cur > 0 && t.charAt(cur-1)==' ' && ".!?".indexOf(b.charAt(b.length()-1)) >= 0)) ? m : 0; }
        public String toString() { return t.toString().replace("\n", "\\n") + (cs >= 0 ? "  [composing " + t.substring(cs, ce) + "]" : ""); }
    }
    static Field f; static TBoardService s; static KeyGeometry geo = KeyGeometry.qwerty(1.5f); static Random rnd = new Random(7);
    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        Dictionary d = Dictionary.load(new FileInputStream(a[0]));
        UserModel u = new UserModel(); Lexicon lex = new Lexicon(d, u);
        s = new TBoardService();
        s.onCreate();
        set("lex", lex); set("tap", new TapDecoder(lex, geo)); set("swipe", new SwipeDecoder(lex, geo));
        s.onCreateInputView();
        InputMethodService.testKeys = k -> { if (k == 67 && f.cur > 0) { f.t.deleteCharAt(f.cur - 1); f.cur--; } else if (k == 21) f.cur = Math.max(0, f.cur-1); else if (k == 22) f.cur = Math.min(f.t.length(), f.cur+1); };

        start(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0);
        type("teh "); check("autocorrect teh -> The", "The ");
        key(-5); check("backspace undoes autocorrect", "Teh ");
        type("teh "); check("reverted word is not corrected again", "Teh teh ");

        start(InputType.TYPE_CLASS_TEXT, 0);
        type("hi  "); check("double-space period is off by default", "Hi  ");
        check2("offensive words are not blocked by default", !((TapDecoder) get("tap")).blockOffensive);

        ContextWrapper.PREFS.put(Prefs.DOUBLE_SPACE_PERIOD, true);
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("ok  "); check("double-space period when turned on", "Ok. ");
        key(-5); key(-5); type("  "); check("no period again after backspacing it", "Ok  ");
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("yes "); key(-5); type("   "); check("backspace then spaces never makes a period", "Yes   ");
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
        key(-5); check("backspace after a swipe deletes one letter", "Hello world. Ho  [composing Ho]");
        key(-5); key(-5);
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
        start(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND);
        type("i recieve"); key(-6); check("enter sends the last word exactly as typed (no autocorrect)", "I recieve");
        start(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0);
        type("we recieve"); key(-6); type("we recieve "); check("enter adds a new line without autocorrect; space still corrects", "We recieve\\nwe receive ");
        check2("a typo sent with enter is not learned as a word", !((Lexicon) get("lex")).isKnown("recieve"));

        ContextWrapper.PREFS.put(Prefs.UNDO_AUTOCORRECT, false);
        ContextWrapper.PREFS.put(Prefs.DOUBLE_SPACE_PERIOD, false);
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("wjat "); key(-5); check("undo-autocorrect setting off: backspace reopens the word instead", "What  [composing What]");
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

        // fixing an autocorrection by hand teaches the keyboard what that typo really meant
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("i hte it "); check("setup: hte is autocorrected to the", "I the it ");
        f.cur = 5; sel();                       // cursor right after "the"
        key(-5); key(-5); key(-5);              // backspace reopens the word, then deletes it
        type("hate");
        f.cur = f.t.length(); sel();            // tap at the end of the text
        check("word fixed by hand", "I hate it ");
        UserModel um = ((Lexicon) get("lex")).user;
        check2("learned hte -> hate (got " + um.correctionFor("hte") + ")", "hate".equals(um.correctionFor("hte")));
        type("i hte this "); check("the learned fix is applied by itself", "I hate it I hate this ");
        UserModel back = new UserModel(); back.load(new StringReader(um.serialize()));
        check2("learned fixes survive saving and loading", "hate".equals(back.correctionFor("hte")));

        // editing inside a word teaches it too
        ContextWrapper.PREFS.put(Prefs.AUTOCORRECT, false);
        start(InputType.TYPE_CLASS_TEXT, 0);
        type("so wnat now ");
        ContextWrapper.PREFS.remove(Prefs.AUTOCORRECT);
        s.onStartInputView(editorOf(InputType.TYPE_CLASS_TEXT), true); // settings re-read, text kept
        f.cur = 5; sel();                       // between "wn" and "at"
        key(-5); type("h");                     // wnat -> what, from the middle
        f.cur = f.t.length(); sel();
        check("edited inside the word", "So what now ");
        check2("learned wnat -> what (got " + um.correctionFor("wnat") + ")", "what".equals(um.correctionFor("wnat")));
        type("wnat "); check("in-word fix is applied by itself", "So what now what ");

        // the learned model stays bounded however much is typed
        UserModel big = new UserModel();
        for (int i = 0; i < 45000; i++) { String w = "w" + Integer.toString(i, 26).replace('0', 'z'); big.learn("p" + i, w, false); big.learn("p" + i, w, false); }
        check2("learned words stay under the limit (" + big.learnedWordCount() + ")", big.learnedWordCount() <= 40000);
        check2("next-word data stays bounded too", big.serialize().split("\nB\t", -1).length <= 40001);

        // emoji search: letters go to the search box, not the app
        start(InputType.TYPE_CLASS_TEXT, 0);
        ArrayList<String> hearts = new EmojiSearch().search("red heart", 5, null, null);
        check2("search 'red heart' finds the red heart first (" + hearts + ")", !hearts.isEmpty() && hearts.get(0).equals("\u2764\ufe0f"));
        ArrayList<String> thumbs = new EmojiSearch().search("thumbs up", 5, null, null);
        check2("search 'thumbs up' finds the thumbs up (" + thumbs + ")", !thumbs.isEmpty() && thumbs.get(0).equals("\ud83d\udc4d"));
        ArrayList<String> heart = new EmojiSearch().search("heart", 5, null, null);
        check2("search 'heart' puts the red heart first (" + heart + ")", !heart.isEmpty() && heart.get(0).equals("\u2764\ufe0f"));
        ArrayList<String> cry = new EmojiSearch().search("cry", 3, null, null);
        check2("search 'cry' finds crying faces (" + cry + ")", cry.contains("\ud83d\ude22") || cry.contains("\ud83d\ude2d"));
        s.onEmojiSearch();
        type("cat");
        check("search typing doesn't reach the text field", "");
        check2("search box holds the query", "cat".equals(get("searchQuery").toString()));
        s.onSearchPick("\ud83d\udc31"); check("picked emoji is typed", "\ud83d\udc31");
        key(-6); type("ok"); check("enter closes search, typing goes to the field again", "\ud83d\udc31ok  [composing ok]");

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1); // the keyboard's background I/O thread would otherwise keep the JVM alive
    }

    static void start(int inputType, int action) throws Exception {
        f = new Field(); InputMethodService.testIC = f;
        EditorInfo e = new EditorInfo(); e.inputType = inputType; e.imeOptions = action;
        s.onStartInputView(e, false);
    }
    static EditorInfo editorOf(int inputType) { EditorInfo e = new EditorInfo(); e.inputType = inputType; return e; }
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
