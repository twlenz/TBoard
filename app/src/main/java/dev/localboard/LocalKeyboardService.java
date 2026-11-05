package dev.localboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Locale;

import dev.localboard.engine.Candidate;
import dev.localboard.engine.KeyGeometry;
import dev.localboard.engine.Lexicon;
import dev.localboard.engine.SwipeDecoder;
import dev.localboard.engine.TapDecoder;

/**
 * The keyboard. Keeps the word being typed as "composing" text in the app's
 * text field so it can still be corrected or replaced, and commits it on
 * space, punctuation, enter, or a suggestion tap.
 */
public final class LocalKeyboardService extends InputMethodService
        implements KeyboardView.Listener, EmojiPanel.Listener, EmojiSearchBar.Listener {

    private SharedPreferences prefs;
    private Engine engine;
    private Lexicon lex;
    private TapDecoder tap;
    private SwipeDecoder swipe;
    private KeyGeometry geometry = KeyGeometry.qwerty(1.5f);

    private FrameLayout container;
    private KeyboardView kv;
    private EmojiPanel emoji;
    // emoji search: the letter keys type into the search bar instead of the app
    private EmojiSearchBar searchBar;
    private EmojiSearch emojiSearch;
    private boolean searching;
    private final StringBuilder searchQuery = new StringBuilder();
    private final KeyboardLayout qwerty = KeyboardLayout.qwerty();
    private final KeyboardLayout symbols = KeyboardLayout.symbols();
    private final KeyboardLayout symbols2 = KeyboardLayout.symbols2();
    private AudioManager audio;

    // what the current field allows
    private EditorInfo editor;
    private boolean allowSuggestions, allowCorrection, allowLearning, allowSwipe, allowAutoCap, allowPredictions;
    /** Passwords and non-text fields: characters go straight in, with no composing word and no smart edits. */
    private boolean rawInput;
    /** Double-space period and punctuation spacing; off in passwords, emails and URLs. */
    private boolean allowSmartPunct;
    private boolean prefUndo, prefDoubleSpace, prefPunctSwap, prefAutoSpace, prefVibrate, prefSound;

    // the word being typed
    private final StringBuilder composing = new StringBuilder();
    private final ArrayList<float[]> points = new ArrayList<>();
    private boolean fromSwipe, pickedExplicit, firstCharAuto;
    private final ArrayList<String> swipeAlts = new ArrayList<>();
    private TapDecoder.Result cachedResult;
    private String cachedFor;

    // state right after a word was committed
    private String corrOriginal, corrResult, corrSep, corrPrev;
    private boolean spaceWasAuto, pendingSpace, lastWasSpace;
    /** Set by backspace, cleared by any other character: two spaces after a backspace never make a period. */
    private boolean doubleSpaceBlocked;

    // correcting words that were already committed
    /** A committed word reopened for editing (backspace into it, or typing at its end), as it was before. */
    private String resumeOriginal;
    /** Text before the cursor when a reopened word was deleted completely; a new word typed right there replaces it. */
    private String resumeEmptiedBefore;
    /** A word being edited in the middle (cursor inside it): how it looked before, and now. */
    private String editOriginal, editCurrent;
    /** Recent autocorrections, corrected word -> what was typed, to learn when one is fixed by hand. */
    private final java.util.LinkedHashMap<String, String> recentCorrections = new java.util.LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) {
            return size() > 30;
        }
    };

    // shift: 0 off, 1 next letter, 2 caps lock
    private int shiftState;
    private boolean autoShift, suppressAutoShift;
    private long lastShiftTap;

    // suggestion bar contents (actual words to insert)
    private final String[] strip = new String[3];
    private int learnedSinceSave;

    /** Applies settings changes at once, e.g. while typing in the settings screen's test field. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> {
        if (EmojiPanel.RECENTS.equals(key)) return;
        applyPrefs();
        if (editor != null) configureField(editor);
        updateShift();
        updateSuggestions();
    };

    // ================================================================ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        engine = Engine.get(this);
        engine.whenReady(() -> {
            lex = engine.lexicon;
            tap = new TapDecoder(lex, geometry);
            swipe = new SwipeDecoder(lex, geometry);
            applyPrefs();
            updateSuggestions();
        });
    }

    @Override
    public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (engine != null) engine.save();
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public View onCreateInputView() {
        kv = new KeyboardView(this);
        kv.setListener(this);
        emoji = new EmojiPanel(this);
        emoji.setListener(this);
        emoji.setVisibility(View.GONE);
        container = new FrameLayout(this);
        container.addView(kv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(emoji, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        searchBar = new EmojiSearchBar(this);
        searchBar.setListener(this);
        searchBar.setVisibility(View.GONE);
        searching = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(searchBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        applyPrefs();
        return root;
    }

    private void applyPrefs() {
        prefUndo = Prefs.on(prefs, Prefs.UNDO_AUTOCORRECT);
        prefDoubleSpace = Prefs.on(prefs, Prefs.DOUBLE_SPACE_PERIOD);
        prefPunctSwap = Prefs.on(prefs, Prefs.PUNCTUATION_SWAP);
        prefAutoSpace = Prefs.on(prefs, Prefs.AUTO_SPACE);
        prefVibrate = Prefs.on(prefs, Prefs.VIBRATE);
        prefSound = Prefs.on(prefs, Prefs.SOUND);
        boolean block = Prefs.on(prefs, Prefs.BLOCK_OFFENSIVE);
        if (tap != null) tap.blockOffensive = block;
        if (swipe != null) swipe.blockOffensive = block;
        if (kv != null) {
            KeyboardTheme theme = KeyboardTheme.resolve(this, Prefs.theme(prefs));
            kv.setTheme(theme);
            emoji.setTheme(theme);
            if (searchBar != null) searchBar.setTheme(theme);
            String h = Prefs.height(prefs);
            kv.setRowHeightDp("short".equals(h) ? 48f : "tall".equals(h) ? 62f : 55f);
            kv.showTrail = Prefs.on(prefs, Prefs.SWIPE_TRAIL);
            kv.spaceCursor = Prefs.on(prefs, Prefs.SPACE_CURSOR);
            kv.keyPreview = Prefs.on(prefs, Prefs.KEY_PREVIEW);
            kv.numberHints = Prefs.on(prefs, Prefs.NUMBER_HINTS);
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        editor = info;
        applyPrefs();
        configureField(info);

        if (kv != null) {
            int cls = info.inputType & InputType.TYPE_MASK_CLASS;
            boolean numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                    || cls == InputType.TYPE_CLASS_DATETIME;
            kv.setLayout(numeric ? symbols : qwerty);
            kv.setEnterLabel(enterLabel(info));
            endSearch(false);
            showEmoji(false);
        }
        resetWordState();
        corrResult = null;
        pendingSpace = false;
        spaceWasAuto = false;
        lastWasSpace = false;
        shiftState = 0;
        suppressAutoShift = false;
        doubleSpaceBlocked = false;
        resumeOriginal = resumeEmptiedBefore = null;
        editOriginal = editCurrent = null;
        updateShift();
        updateSuggestions();
    }

    /** Works out which features this text field allows, from its input type and the settings. */
    private void configureField(EditorInfo info) {
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        int flags = info.inputType & InputType.TYPE_MASK_FLAGS;
        boolean text = cls == InputType.TYPE_CLASS_TEXT;
        boolean password = (text && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        boolean special = text && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_URI
                || var == InputType.TYPE_TEXT_VARIATION_FILTER);
        boolean noSuggest = (flags & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0;
        boolean incognito = (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;

        allowSuggestions = text && !password && !noSuggest && Prefs.on(prefs, Prefs.SUGGESTIONS);
        allowCorrection = text && !password && !special && !noSuggest && Prefs.on(prefs, Prefs.AUTOCORRECT);
        allowLearning = text && !password && !special && !incognito && Prefs.on(prefs, Prefs.LEARN);
        allowSwipe = text && !password && Prefs.on(prefs, Prefs.SWIPE);
        allowAutoCap = text && !password && !special && Prefs.on(prefs, Prefs.AUTO_CAPITALIZE);
        allowPredictions = allowSuggestions && Prefs.on(prefs, Prefs.NEXT_WORD);
        rawInput = password || !text;
        allowSmartPunct = text && !password && !special;
        if (kv != null) kv.swipeEnabled = allowSwipe;
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (kv != null) kv.cancelTouch();
        if (searching) endSearch(false);
        engine.save();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        finishEdit();
        resumeOriginal = null;
        resetWordState();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (composing.length() > 0 && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // Either the person moved the cursor, or this report is stale because we typed
            // faster than the app answered. Check the text itself before giving up the word.
            InputConnection ic = getCurrentInputConnection();
            boolean stillHere = false;
            if (ic != null) {
                CharSequence before = ic.getTextBeforeCursor(composing.length(), 0);
                CharSequence sel = ic.getSelectedText(0);
                stillHere = before != null && before.toString().equals(composing.toString())
                        && (sel == null || sel.length() == 0);
                if (!stillHere) ic.finishComposingText();
            }
            if (!stillHere) {
                // a reopened word the person finished editing and then left: learn from it as it stands
                if (resumeOriginal != null) {
                    String now = composing.toString();
                    if (allowLearning && lex != null && !resumeOriginal.equalsIgnoreCase(now) && isWordOnly(now)) {
                        lex.user.learn(null, storageForm(now), false);
                    }
                    learnFromEdit(null, resumeOriginal, now);
                }
                resetWordState();
                resumeOriginal = null;
                corrResult = null;
                pendingSpace = false;
                spaceWasAuto = false;
                updateSuggestions();
            }
        }
        if (composing.length() == 0 && editOriginal != null) {
            // the cursor left the word being edited: learn from what it became
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                String[] w = wordAround(ic);
                if (!(w[0] + w[1]).equals(editCurrent)) finishEdit();
            }
        }
        if (composing.length() == 0) updateShift();
    }

    private static String enterLabel(EditorInfo info) {
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵";
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO:
                return "Go";
            case EditorInfo.IME_ACTION_SEARCH:
                return "Search";
            case EditorInfo.IME_ACTION_SEND:
                return "Send";
            case EditorInfo.IME_ACTION_NEXT:
                return "Next";
            case EditorInfo.IME_ACTION_DONE:
                return "Done";
            case EditorInfo.IME_ACTION_PREVIOUS:
                return "Prev";
            default:
                return "↵";
        }
    }

    // ================================================================ keys

    @Override
    public void onKey(int code, float x, float y) {
        if (searching && searchKey(code)) return;
        if (code != KeyboardLayout.SHIFT && code != KeyboardLayout.DELETE) corrResult = null;
        switch (code) {
            case KeyboardLayout.SHIFT:
                handleShift();
                return;
            case KeyboardLayout.DELETE:
                handleDelete();
                return;
            case KeyboardLayout.ENTER:
                handleEnter();
                return;
            case KeyboardLayout.SYMBOLS:
                kv.setLayout(symbols);
                return;
            case KeyboardLayout.SYMBOLS2:
                kv.setLayout(symbols2);
                return;
            case KeyboardLayout.ALPHA:
                kv.setLayout(qwerty);
                updateShift();
                return;
            case KeyboardLayout.EMOJI:
                showEmoji(true);
                return;
            case ' ':
                handleSpace();
                return;
            default:
                handleChar((char) code, x, y);
        }
    }

    @Override
    public void onText(String text) {
        if (searching) {
            searchQuery.append(text.toLowerCase(Locale.ROOT));
            updateSearch();
            return;
        }
        corrResult = null;
        if (text.length() == 1) {
            handleChar(text.charAt(0), Float.NaN, Float.NaN);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (composing.length() > 0) finishWord(false, "");
        ic.commitText(text, 1);
        afterNonSpace();
    }

    private void handleChar(char c, float x, float y) {
        if (rawInput) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            boolean shifted = shiftState > 0 || autoShift;
            ic.commitText(String.valueOf(shifted ? Character.toUpperCase(c) : c), 1);
            if (shiftState == 1) shiftState = 0;
            afterNonSpace();
            updateShift();
            return;
        }
        boolean letter = Character.isLetter(c);
        boolean joins = composing.length() > 0 && (c == '\'' || c == '-' || Character.isDigit(c));
        if (composing.length() == 0 && (letter || c == '\'')) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && letterAfterCursor(ic)) {
                insertInsideWord(ic, c);
                return;
            }
        }
        if (letter || joins) {
            appendToWord(c, x, y);
        } else if (Character.isDigit(c)) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            insertPendingSpace(ic);
            ic.commitText(String.valueOf(c), 1);
            afterNonSpace();
        } else {
            handleSeparator(c);
        }
    }

    private void appendToWord(char c, float x, float y) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (fromSwipe) finishWord(false, ""); // tapping after a swipe starts a new word
        if (composing.length() == 0) {
            boolean reopened = !(pendingSpace && prefAutoSpace) && reopenForTyping(ic);
            if (!reopened) {
                keepResumeOnlyIfHere(ic);
                if (pendingSpace && prefAutoSpace) {
                    insertPendingSpace(ic);
                    updateShift(); // the new space may start a sentence
                }
            }
            pendingSpace = false;
            firstCharAuto = !reopened && autoShift && shiftState == 0;
        }
        boolean shifted = shiftState > 0 || autoShift;
        composing.append(shifted ? Character.toUpperCase(c) : c);
        points.add(Float.isNaN(x) ? null : new float[]{x, y});
        if (shiftState == 1) shiftState = 0;
        suppressAutoShift = false;
        ic.setComposingText(composing, 1);
        afterNonSpace();
        updateShift();
        updateSuggestions();
    }

    private void handleSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (composing.length() == 0) endEditAtSeparator(ic);
        if (composing.length() > 0) {
            finishWord(true, " ");
            spaceWasAuto = true;
        } else if (prefDoubleSpace && allowSmartPunct && !doubleSpaceBlocked && lastWasSpace && endsWithWordAndSpace(ic)) {
            ic.deleteSurroundingText(1, 0);
            ic.commitText(". ", 1);
            spaceWasAuto = true;
            pendingSpace = false;
            lastWasSpace = false;
            updateShift();
            updateSuggestions();
            return;
        } else {
            ic.commitText(" ", 1);
            spaceWasAuto = false;
        }
        pendingSpace = false;
        lastWasSpace = true;
        updateShift();
        updateSuggestions();
    }

    private static boolean endsWithWordAndSpace(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(2, 0);
        if (b == null || b.length() < 2) return false;
        return b.charAt(1) == ' ' && Character.isLetterOrDigit(b.charAt(0));
    }

    private void handleSeparator(char c) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String s = String.valueOf(c);
        boolean closing = ".,?!:;)".indexOf(c) >= 0;
        doubleSpaceBlocked = false;
        if (composing.length() == 0) endEditAtSeparator(ic);
        if (composing.length() > 0) {
            finishWord(true, s);
            spaceWasAuto = false;
        } else if (closing && prefPunctSwap && allowSmartPunct && spaceWasAuto && endsWithSpace(ic)) {
            ic.deleteSurroundingText(1, 0);
            boolean space = c != ')';
            ic.commitText(space ? s + " " : s, 1);
            spaceWasAuto = space;
            pendingSpace = false;
            lastWasSpace = false;
            updateShift();
            updateSuggestions();
            return;
        } else {
            if ("([{\"“".indexOf(c) >= 0) insertPendingSpace(ic);
            ic.commitText(s, 1);
            spaceWasAuto = false;
        }
        pendingSpace = false;
        lastWasSpace = false;
        updateShift();
        updateSuggestions();
    }

    private static boolean endsWithSpace(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        return b != null && b.length() == 1 && b.charAt(0) == ' ';
    }

    private void handleEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || editor == null) return;
        if (composing.length() == 0) endEditAtSeparator(ic);
        if (composing.length() > 0) finishWord(true, "");
        corrResult = null;
        int action = editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = (editor.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendKeyChar('\n');
        }
        afterNonSpace();
        pendingSpace = false;
        updateShift();
        updateSuggestions();
    }

    private void handleDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastWasSpace = false;
        doubleSpaceBlocked = true;
        if (composing.length() > 0) {
            deleteFromComposing(ic);
        } else if (corrResult != null && prefUndo) {
            String expect = corrResult + corrSep;
            CharSequence before = ic.getTextBeforeCursor(expect.length(), 0);
            if (before != null && before.toString().equals(expect)) {
                // put back exactly what was typed, and remember it as a word the person wants
                ic.deleteSurroundingText(expect.length(), 0);
                ic.commitText(corrOriginal + corrSep, 1);
                if (allowLearning && lex != null) {
                    lex.user.unlearn(corrPrev, storageForm(corrResult));
                    lex.user.confirm(storageForm(corrOriginal));
                    lex.user.learn(corrPrev, storageForm(corrOriginal), true);
                }
                corrResult = null;
                updateShift();
                updateSuggestions();
                return;
            }
            corrResult = null;
            deleteBeforeCursor(ic);
        } else {
            corrResult = null;
            deleteBeforeCursor(ic);
        }
        spaceWasAuto = false;
        pendingSpace = false;
        updateShift();
        updateSuggestions();
    }

    /** Deletes the last letter of the word being typed (a swiped word too, letter by letter). */
    private void deleteFromComposing(InputConnection ic) {
        if (fromSwipe) {
            fromSwipe = false;
            pickedExplicit = false;
            swipeAlts.clear();
            points.clear();
            for (int i = 0; i < composing.length(); i++) points.add(null);
        }
        composing.deleteCharAt(composing.length() - 1);
        if (!points.isEmpty()) points.remove(points.size() - 1);
        if (composing.length() == 0) {
            ic.commitText("", 1);
            resetWordState();
            if (resumeOriginal != null) resumeEmptiedBefore = textBefore(ic);
        } else {
            ic.setComposingText(composing, 1);
        }
    }

    /**
     * Deletes one character. Plain letters and punctuation are deleted directly, so the text is up to
     * date at once and the word before the cursor can be reopened for correcting; anything else
     * (emoji, an empty field) goes as a backspace key so the app handles it.
     */
    private void deleteBeforeCursor(InputConnection ic) {
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
            return;
        }
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        if (rawInput || b == null || b.length() == 0 || b.charAt(0) >= 0x300) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            return;
        }
        String[] w = wordAround(ic);
        boolean inside = !w[0].isEmpty() && !w[1].isEmpty();
        if (!w[0].isEmpty() && w[1].isEmpty() && reopenWordBeforeCursor(ic)) {
            deleteFromComposing(ic); // at the end of a word: reopen it whole, then delete its last letter
            return;
        }
        ic.deleteSurroundingText(1, 0);
        if (inside) {
            String[] now = wordAround(ic);
            noteEdit(w[0] + w[1], now[0] + now[1]);
        } else {
            reopenWordBeforeCursor(ic);
        }
    }

    private void handleShift() {
        long now = SystemClock.uptimeMillis();
        if (shiftState == 2) {
            shiftState = 0;
        } else if (shiftState == 1) {
            shiftState = now - lastShiftTap < 400 ? 2 : 0;
        } else if (autoShift) {
            shiftState = 0;
            suppressAutoShift = true;
        } else {
            // a quick second tap after turning auto-capitals off means caps lock
            shiftState = suppressAutoShift && now - lastShiftTap < 400 ? 2 : 1;
        }
        lastShiftTap = now;
        updateShift();
    }

    private void afterNonSpace() {
        spaceWasAuto = false;
        lastWasSpace = false;
        doubleSpaceBlocked = false;
    }

    private void insertPendingSpace(InputConnection ic) {
        if (pendingSpace && prefAutoSpace) ic.commitText(" ", 1);
        pendingSpace = false;
    }

    // ================================================================ words

    /** Commits the composing word (autocorrected if allowed), followed by {@code sep}. */
    private void finishWord(boolean correct, String sep) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || composing.length() == 0) return;
        String typed = composing.toString();
        String prev = previousWord();
        String fin = typed;
        boolean corrected = false;
        String reopened = resumeOriginal;
        resumeOriginal = resumeEmptiedBefore = null;
        if (!fromSwipe && lex != null && allowCorrection) {
            TapDecoder.Result r = decodeCurrent(prev);
            if (correct && allowCorrection && !pickedExplicit && r.correction != null && !hasInnerCaps(typed)) {
                fin = applyCase(r.correction.display, typed);
                corrected = !fin.equals(typed);
            } else if (r.typedKnown && !hasInnerCaps(typed)) {
                // e.g. "i" -> "I", "monday" -> "Monday"
                String lower = typed.toLowerCase(Locale.ROOT);
                fin = applyCase(lex.display(lower, lex.dict.find(lower)), typed);
            }
        }
        ic.commitText(fin + sep, 1);
        if (corrected) {
            corrOriginal = typed;
            corrResult = fin;
            corrSep = sep;
            corrPrev = prev;
            recentCorrections.put(fin.toLowerCase(Locale.ROOT), typed.toLowerCase(Locale.ROOT));
        } else {
            corrResult = null;
        }
        if (reopened != null && reopened.equalsIgnoreCase(fin)) {
            resetWordState(); // reopened and left as it was: it was already learned the first time
            return;
        }
        learnWord(prev, fin, typed, corrected, pickedExplicit);
        if (reopened != null) learnFromEdit(prev, reopened, fin);
        resetWordState();
    }

    // ================================================================ correcting committed words

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'';
    }

    private static boolean isWordOnly(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!isWordChar(s.charAt(i))) return false;
        return true;
    }

    /** The word characters just before and just after the cursor. */
    private static String[] wordAround(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(48, 0), a = ic.getTextAfterCursor(48, 0);
        String before = b == null ? "" : b.toString(), after = a == null ? "" : a.toString();
        int i = before.length();
        while (i > 0 && isWordChar(before.charAt(i - 1))) i--;
        int j = 0;
        while (j < after.length() && isWordChar(after.charAt(j))) j++;
        return new String[]{before.substring(i), after.substring(0, j)};
    }

    private static boolean letterAfterCursor(InputConnection ic) {
        CharSequence a = ic.getTextAfterCursor(1, 0);
        return a != null && a.length() == 1 && Character.isLetter(a.charAt(0));
    }

    private static String textBefore(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(24, 0);
        return b == null ? "" : b.toString();
    }

    /**
     * When typing starts with no word in progress: if a whole word is selected, it is being retyped;
     * if the cursor is at the end of a word, typing continues that word. Either way the old word is
     * remembered so a correction can be learned. Returns true if a word was reopened.
     */
    private boolean reopenForTyping(InputConnection ic) {
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            String[] w = wordAround(ic);
            if (isWordOnly(sel.toString()) && w[0].isEmpty() && w[1].isEmpty()) {
                finishEdit();
                resumeOriginal = sel.toString(); // the composing text replaces the selection
                resumeEmptiedBefore = null;
                return true;
            }
            return false;
        }
        return reopenWordBeforeCursor(ic);
    }

    /** Turns the word just before the cursor back into the word being typed. */
    private boolean reopenWordBeforeCursor(InputConnection ic) {
        if (rawInput || composing.length() > 0) return false;
        String[] w = wordAround(ic);
        String word = w[0];
        if (word.isEmpty() || !w[1].isEmpty() || word.length() > 40 || !Character.isLetter(word.charAt(0))) return false;
        String original = word;
        if (editOriginal != null) {
            if (word.equals(editCurrent)) {
                original = editOriginal; // finishing an edit made in the middle of this word
                editOriginal = editCurrent = null;
            } else {
                finishEdit();
            }
        }
        ic.beginBatchEdit();
        ic.deleteSurroundingText(word.length(), 0);
        ic.setComposingText(word, 1);
        ic.endBatchEdit();
        resetWordState();
        composing.append(word);
        for (int i = 0; i < word.length(); i++) points.add(null);
        resumeOriginal = original;
        resumeEmptiedBefore = null;
        firstCharAuto = false;
        return true;
    }

    /** A reopened word deleted completely carries over only to a new word typed in the same place. */
    private void keepResumeOnlyIfHere(InputConnection ic) {
        if (resumeOriginal != null && (resumeEmptiedBefore == null || !resumeEmptiedBefore.equals(textBefore(ic)))) {
            resumeOriginal = null;
        }
        resumeEmptiedBefore = null;
    }

    /** Types a character in the middle of an existing word, without autocorrect, and tracks the edit. */
    private void insertInsideWord(InputConnection ic, char c) {
        String[] w = wordAround(ic);
        CharSequence sel = ic.getSelectedText(0);
        String selected = sel == null ? "" : sel.toString();
        boolean shifted = shiftState > 0 || autoShift;
        char out = shifted ? Character.toUpperCase(c) : c;
        ic.commitText(String.valueOf(out), 1);
        if (selected.isEmpty() || isWordOnly(selected)) noteEdit(w[0] + selected + w[1], w[0] + out + w[1]);
        corrResult = null;
        pendingSpace = false;
        if (shiftState == 1) shiftState = 0;
        suppressAutoShift = false;
        afterNonSpace();
        updateShift();
        updateSuggestions();
    }

    private void noteEdit(String before, String after) {
        if (editOriginal == null || !before.equals(editCurrent)) {
            finishEdit();
            editOriginal = before;
        }
        editCurrent = after;
    }

    /** A space or punctuation typed while editing inside a word: learn if the cursor is at the word's end. */
    private void endEditAtSeparator(InputConnection ic) {
        if (editOriginal == null) return;
        if (letterAfterCursor(ic)) editOriginal = editCurrent = null; // splitting the word, not correcting it
        else finishEdit();
    }

    private void finishEdit() {
        String o = editOriginal, n = editCurrent;
        editOriginal = editCurrent = null;
        if (o == null || n == null || o.equals(n)) return;
        if (allowLearning && lex != null && n.length() >= 2 && isWordOnly(n)) lex.user.learn(null, storageForm(n), false);
        learnFromEdit(null, o, n);
    }

    /**
     * The person changed a committed word {@code orig} into {@code now} by hand. If {@code orig} was an
     * autocorrection and they put back what they typed, stop making that correction. If it was a
     * misspelling and {@code now} is a real word, autocorrect that misspelling to it from now on.
     */
    private void learnFromEdit(String prev, String orig, String now) {
        if (!allowLearning || lex == null) return;
        String o = orig.toLowerCase(Locale.ROOT), n = now.toLowerCase(Locale.ROOT);
        if (o.equals(n) || n.length() < 2 || !isWordOnly(o) || !isWordOnly(n)) return;
        lex.user.unlearn(prev, storageForm(orig));
        String typed = recentCorrections.get(o);
        String source = typed != null ? typed : o;
        if (source.equals(n)) {
            lex.user.confirm(storageForm(now));
            return;
        }
        if (source.length() >= 2 && !lex.isKnown(source) && lex.isKnown(n) && closeEnough(source, n)) {
            lex.user.learnCorrection(source, n);
        }
    }

    /** True if b is a and a few typos away (at most 2 edits, and not more than a third of the word). */
    static boolean closeEnough(String a, String b) {
        int limit = Math.min(2, Math.max(1, Math.max(a.length(), b.length()) / 3));
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > limit) return false;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m] <= limit;
    }

    private void learnWord(String prev, String fin, String typed, boolean corrected, boolean explicit) {
        if (!allowLearning || lex == null) return;
        String key = storageForm(fin);
        lex.user.learn(prev, key, explicit);
        // learn where this person taps each key, from words we are confident about
        if (!fromSwipe && lex.isKnown(key.toLowerCase(Locale.ROOT)) && fin.length() == typed.length()
                && fin.length() == points.size()) {
            String lower = fin.toLowerCase(Locale.ROOT);
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                float[] p = points.get(i);
                if (p == null || !geometry.has(c)) continue;
                lex.user.learnTouch(c, p[0] - geometry.x(c), p[1] - geometry.y(c));
            }
        }
        if (++learnedSinceSave >= 25) {
            learnedSinceSave = 0;
            engine.save();
        }
    }

    /**
     * The form a word is learned in. A capital that only came from sentence-start
     * auto-capitalization is dropped ("The" is learned as "the"); a capital the
     * person added with shift on an unknown word is kept, since that is usually a name.
     */
    private String storageForm(String w) {
        if (w.length() > 1 && Character.isUpperCase(w.charAt(0)) && !hasInnerCaps(w) && lex != null) {
            String lower = w.toLowerCase(Locale.ROOT);
            int id = lex.dict.find(lower);
            if (id >= 0) return lex.dict.display[id];
            if (firstCharAuto) return lower;
        }
        return w;
    }

    private static boolean hasInnerCaps(String w) {
        boolean allUpper = w.length() > 1;
        boolean inner = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetter(c) && !Character.isUpperCase(c)) allUpper = false;
            if (i > 0 && Character.isUpperCase(c)) inner = true;
        }
        return inner && !allUpper;
    }

    /** Gives {@code word} the capitalization the person typed: ALL CAPS, Capitalized, or as in the dictionary. */
    private static String applyCase(String word, String typed) {
        if (word.isEmpty() || typed.isEmpty()) return word;
        boolean allUpper = typed.length() > 1;
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (Character.isLetter(c) && !Character.isUpperCase(c)) allUpper = false;
        }
        if (allUpper) return word.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(typed.charAt(0))) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word;
    }

    private String casedForContext(String word) {
        if (composing.length() > 0) return applyCase(word, composing.toString());
        if (shiftState == 2) return word.toUpperCase(Locale.ROOT);
        if (shiftState == 1 || autoShift) return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        return word;
    }

    private TapDecoder.Result decodeCurrent(String prev) {
        String key = composing.toString() + "|" + points.size() + "|" + prev;
        if (cachedResult != null && key.equals(cachedFor)) return cachedResult;
        int n = composing.length();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            float[] p = i < points.size() ? points.get(i) : null;
            xs[i] = p == null ? Float.NaN : p[0];
            ys[i] = p == null ? Float.NaN : p[1];
        }
        cachedResult = tap.decode(composing.toString(), xs, ys, prev);
        cachedFor = key;
        return cachedResult;
    }

    /** The word before the composing word (or before the cursor), lowercase; null at a sentence break. */
    private String previousWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        CharSequence cs = ic.getTextBeforeCursor(80, 0);
        if (cs == null) return null;
        String t = cs.toString();
        if (composing.length() > 0 && t.endsWith(composing.toString())) {
            t = t.substring(0, t.length() - composing.length());
        }
        int i = t.length();
        while (i > 0 && t.charAt(i - 1) == ' ') i--;
        int end = i;
        while (i > 0 && (Character.isLetter(t.charAt(i - 1)) || t.charAt(i - 1) == '\'')) i--;
        if (i == end) return null;
        return t.substring(i, end).toLowerCase(Locale.ROOT);
    }

    private void resetWordState() {
        composing.setLength(0);
        points.clear();
        fromSwipe = false;
        pickedExplicit = false;
        swipeAlts.clear();
        cachedResult = null;
        cachedFor = null;
    }

    // ================================================================ swipe

    @Override
    public boolean onSwipe(float[] xs, float[] ys, int count) {
        if (searching) return false;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || swipe == null || !allowSwipe) return false;
        // Decode before touching the text: if nothing matches, the view types the key the
        // finger started on instead, and a half-typed word must not have been committed.
        String prev = composing.length() > 0 ? trailingWord(composing.toString()) : previousWord();
        ArrayList<Candidate> res = swipe.decode(xs, ys, count, prev, 5);
        if (res.isEmpty()) return false;
        corrResult = null;
        if (composing.length() > 0) {
            if (fromSwipe) finishWord(false, "");
            else {
                finishWord(true, " ");
                pendingSpace = false;
            }
        }
        boolean needSpace = pendingSpace;
        if (!needSpace) {
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            if (b != null && b.length() == 1) {
                char ch = b.charAt(0);
                needSpace = !Character.isWhitespace(ch) && "([{\"'@#/-“‘".indexOf(ch) < 0;
            }
        }
        keepResumeOnlyIfHere(ic);
        if (needSpace && prefAutoSpace) ic.commitText(" ", 1);
        pendingSpace = false;
        updateShift(); // capitalize if the swipe starts a sentence

        firstCharAuto = autoShift && shiftState == 0;
        swipeAlts.clear();
        for (int i = 1; i < res.size(); i++) swipeAlts.add(caseSwiped(res.get(i).display));
        String w = caseSwiped(res.get(0).display);
        composing.setLength(0);
        composing.append(w);
        points.clear();
        fromSwipe = true;
        pickedExplicit = false;
        ic.setComposingText(w, 1);
        pendingSpace = true;
        if (shiftState == 1) shiftState = 0;
        afterNonSpace();
        updateShift();
        updateSuggestions();
        return true;
    }

    /** The last run of letters and apostrophes in {@code s}, lowercase, or null. */
    private static String trailingWord(String s) {
        int end = s.length(), i = end;
        while (i > 0 && (Character.isLetter(s.charAt(i - 1)) || s.charAt(i - 1) == '\'')) i--;
        return i == end ? null : s.substring(i, end).toLowerCase(Locale.ROOT);
    }

    private String caseSwiped(String w) {
        if (shiftState == 2) return w.toUpperCase(Locale.ROOT);
        if (shiftState == 1 || autoShift) return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        return w;
    }

    // ================================================================ suggestions

    @Override
    public void onSuggestionPicked(int index) {
        if (searching) return;
        InputConnection ic = getCurrentInputConnection();
        String word = strip[index];
        if (ic == null || word == null) return;
        corrResult = null;
        if (composing.length() == 0) {
            // a next-word prediction
            String prev = previousWord();
            ic.commitText(word + " ", 1);
            if (allowLearning && lex != null) lex.user.learn(prev, storageForm(word), false);
            spaceWasAuto = true;
            lastWasSpace = false;
        } else if (fromSwipe) {
            String old = composing.toString();
            swipeAlts.remove(word);
            swipeAlts.add(0, old);
            composing.setLength(0);
            composing.append(word);
            pickedExplicit = true;
            ic.setComposingText(word, 1);
        } else {
            pickedExplicit = true;
            String typed = composing.toString();
            String prev = previousWord();
            String reopened = resumeOriginal;
            resumeOriginal = resumeEmptiedBefore = null;
            ic.commitText(word + " ", 1);
            learnWord(prev, word, typed, false, true);
            if (reopened != null) learnFromEdit(prev, reopened, word);
            resetWordState();
            spaceWasAuto = true;
            lastWasSpace = false;
        }
        pendingSpace = false;
        doubleSpaceBlocked = false;
        updateShift();
        updateSuggestions();
    }

    private void updateSuggestions() {
        if (kv == null) return;
        strip[0] = strip[1] = strip[2] = null;
        if (!allowSuggestions || lex == null || searching) {
            kv.setSuggestions(null, null, null, -1);
            return;
        }
        String[] labels = new String[3];
        int bold = -1;
        if (composing.length() == 0) {
            if (allowPredictions && lastWasSpaceOrAfterWord()) {
                ArrayList<String> preds = lex.user.predict(previousWord(), 3);
                int[] order = {1, 0, 2};
                for (int i = 0; i < preds.size(); i++) {
                    String k = preds.get(i);
                    strip[order[i]] = casedForContext(lex.display(k, lex.dict.find(k)));
                }
            }
            for (int i = 0; i < 3; i++) labels[i] = strip[i];
        } else if (fromSwipe) {
            strip[1] = composing.toString();
            if (swipeAlts.size() > 0) strip[0] = swipeAlts.get(0);
            if (swipeAlts.size() > 1) strip[2] = swipeAlts.get(1);
            for (int i = 0; i < 3; i++) labels[i] = strip[i];
            bold = 1;
        } else {
            String typed = composing.toString();
            String lower = typed.toLowerCase(Locale.ROOT);
            TapDecoder.Result r = decodeCurrent(previousWord());
            boolean willCorrect = allowCorrection && !pickedExplicit && r.correction != null && !hasInnerCaps(typed);
            ArrayList<Candidate> all = new ArrayList<>(r.words);
            all.addAll(r.completions);
            java.util.Collections.sort(all, (a, b) -> Float.compare(b.score, a.score));
            ArrayList<String> others = new ArrayList<>();
            ArrayList<String> seen = new ArrayList<>();
            seen.add(lower);
            if (willCorrect) seen.add(r.correction.key);
            for (Candidate c : all) {
                if (seen.contains(c.key)) continue;
                if (c.offensive && tap.blockOffensive) continue;
                seen.add(c.key);
                others.add(applyCase(c.display, typed));
                if (others.size() >= 2) break;
            }
            String typedShown = r.typedKnown ? applyCase(lex.display(lower, lex.dict.find(lower)), typed) : typed;
            if (willCorrect) {
                strip[0] = typed;
                labels[0] = "“" + typed + "”";
                strip[1] = applyCase(r.correction.display, typed);
                labels[1] = strip[1];
                bold = 1;
                if (!others.isEmpty()) strip[2] = labels[2] = others.get(0);
            } else if (r.typedKnown) {
                strip[1] = labels[1] = typedShown;
                if (others.size() > 0) strip[0] = labels[0] = others.get(0);
                if (others.size() > 1) strip[2] = labels[2] = others.get(1);
            } else {
                strip[0] = typed;
                labels[0] = "“" + typed + "”";
                if (others.size() > 0) strip[1] = labels[1] = others.get(0);
                if (others.size() > 1) strip[2] = labels[2] = others.get(1);
            }
        }
        kv.setSuggestions(labels[0], labels[1], labels[2], bold);
    }

    private boolean lastWasSpaceOrAfterWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence b = ic.getTextBeforeCursor(2, 0);
        return b != null && b.length() == 2 && b.charAt(1) == ' ' && Character.isLetter(b.charAt(0));
    }

    // ================================================================ shift

    private void updateShift() {
        boolean auto = false;
        if (!searching && allowAutoCap && shiftState == 0 && !suppressAutoShift && composing.length() == 0
                && kv != null && kv.getLayout().letters) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) auto = ic.getCursorCapsMode(TextUtils.CAP_MODE_SENTENCES) != 0;
        }
        autoShift = auto;
        if (kv != null) kv.setShiftState(shiftState == 2 ? 2 : (shiftState == 1 || autoShift) ? 1 : 0);
    }

    // ================================================================ other keys

    @Override
    public void onCursorMove(int steps) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || searching) return;
        if (composing.length() > 0) {
            ic.finishComposingText();
            resetWordState();
        }
        corrResult = null;
        pendingSpace = false;
        afterNonSpace();
        int code = steps < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        for (int i = 0; i < Math.abs(steps); i++) sendDownUpKeyEvents(code);
        updateSuggestions();
    }

    @Override
    public void onSpaceLongPress() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    @Override
    public void onOpenSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    @Override
    public void onGeometryChanged(KeyGeometry g) {
        geometry = g;
        if (tap != null) tap.setGeometry(g);
        if (swipe != null) swipe.setGeometry(g);
        cachedResult = null;
    }

    @Override
    public void onKeyDownFeedback() {
        if (prefVibrate && kv != null) kv.haptic();
        if (prefSound && audio != null) audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f);
    }

    // ================================================================ emoji

    private void showEmoji(boolean show) {
        if (kv == null || emoji == null) return;
        if (show) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && composing.length() > 0) finishWord(true, "");
            int h = kv.getHeight();
            ViewGroup.LayoutParams lp = emoji.getLayoutParams();
            lp.height = h > 0 ? h : ViewGroup.LayoutParams.WRAP_CONTENT;
            emoji.setLayoutParams(lp);
            emoji.refresh();
            emoji.setVisibility(View.VISIBLE);
            kv.setVisibility(View.INVISIBLE);
        } else {
            emoji.setVisibility(View.GONE);
            kv.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onEmoji(String e) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        corrResult = null;
        pendingSpace = false;
        ic.commitText(e, 1);
        afterNonSpace();
    }

    @Override
    public void onEmojiBack() {
        showEmoji(false);
        updateShift();
        updateSuggestions();
    }

    @Override
    public void onEmojiDelete() {
        handleDelete();
    }

    // ================================================================ emoji search

    @Override
    public void onEmojiSearch() {
        if (kv == null || searchBar == null) return;
        if (emojiSearch == null) emojiSearch = new EmojiSearch();
        searching = true;
        searchQuery.setLength(0);
        showEmoji(false);
        kv.setLayout(qwerty);
        kv.swipeEnabled = false;
        kv.setStripVisible(false); // the search bar takes its place
        shiftState = 0;
        updateShift();
        updateSuggestions();
        searchBar.setVisibility(View.VISIBLE);
        updateSearch();
    }

    /** Handles a key while searching; returns false to let it act normally (layout switches). */
    private boolean searchKey(int code) {
        switch (code) {
            case KeyboardLayout.DELETE:
                if (searchQuery.length() > 0) {
                    searchQuery.setLength(searchQuery.length() - 1);
                    updateSearch();
                } else {
                    endSearch(true);
                }
                return true;
            case KeyboardLayout.ENTER:
                endSearch(false);
                return true;
            case KeyboardLayout.EMOJI:
                endSearch(true);
                return true;
            case KeyboardLayout.SHIFT:
                return true;
            case KeyboardLayout.SYMBOLS:
            case KeyboardLayout.SYMBOLS2:
            case KeyboardLayout.ALPHA:
                return false;
            case ' ':
                if (searchQuery.length() > 0 && searchQuery.charAt(searchQuery.length() - 1) != ' ') searchQuery.append(' ');
                updateSearch();
                return true;
            default:
                if (code > 0) {
                    searchQuery.append(Character.toLowerCase((char) code));
                    updateSearch();
                }
                return true;
        }
    }

    private void updateSearch() {
        if (searchBar == null || emojiSearch == null) return;
        String q = searchQuery.toString();
        ArrayList<String> found = q.trim().isEmpty() ? new ArrayList<String>()
                : emojiSearch.search(q, 60, e -> emoji == null || emoji.canShow(e), emoji == null ? null : emoji.recents());
        searchBar.show(q, found);
    }

    /** Leaves search, back to the emoji panel or to the letters. */
    private void endSearch(boolean toPanel) {
        if (!searching) return;
        searching = false;
        searchQuery.setLength(0);
        if (searchBar != null) searchBar.setVisibility(View.GONE);
        if (kv != null) {
            kv.swipeEnabled = allowSwipe;
            kv.setStripVisible(true);
            kv.setLayout(qwerty);
        }
        showEmoji(toPanel);
        updateShift();
        updateSuggestions();
    }

    @Override
    public void onSearchPick(String e) {
        if (emoji != null) emoji.addRecent(e);
        onEmoji(e);
    }

    @Override
    public void onSearchClose() {
        endSearch(false);
    }
}
