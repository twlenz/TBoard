package dev.tboard.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the keyboard learns from the person, kept only on the device:
 * how often each word is used, which word tends to follow which, words that
 * are not in the dictionary, and where on each key this person's taps land.
 */
public final class UserModel {

    static final class Entry {
        String display;
        int count;
        boolean confirmed;
        /** On the person's "Words to keep" list: never autocorrected, survives clearing learned data. */
        boolean kept;
    }

    private final HashMap<String, Entry> words = new HashMap<>();
    private final HashMap<String, HashMap<String, Integer>> bigrams = new HashMap<>();
    /** Misspellings this person fixed by hand, and what they changed them to. */
    private final HashMap<String, String> corrections = new HashMap<>();
    private long total;
    private final float[] offX = new float[26];
    private final float[] offY = new float[26];
    private final int[] offN = new int[26];
    private boolean dirty;
    private int knownVersion;

    private static final int MAX_WORDS = 40000;
    private static final int MAX_FOLLOWERS = 60;
    private static final int MAX_CORRECTIONS = 2000;
    private static final float MAX_OFFSET = 0.35f;

    // ---------------------------------------------------------------- learning

    /** Records that {@code word} was entered after {@code prev} (either may be null for prev). */
    public void learn(String prev, String word, boolean confirmed) {
        if (word == null || word.isEmpty()) return;
        String k = word.toLowerCase(Locale.ROOT);
        Entry e = words.get(k);
        if (e == null) {
            e = new Entry();
            e.display = word;
            words.put(k, e);
        }
        boolean wasKnown = e.confirmed || e.count >= 2;
        e.count++;
        if (confirmed) e.confirmed = true;
        if (!wasKnown && (e.confirmed || e.count >= 2)) knownVersion++;
        total++;
        if (prev != null && !prev.isEmpty()) {
            String p = prev.toLowerCase(Locale.ROOT);
            HashMap<String, Integer> f = bigrams.get(p);
            if (f == null) {
                f = new HashMap<>();
                bigrams.put(p, f);
            }
            Integer c = f.get(k);
            f.put(k, c == null ? 1 : c + 1);
            if (f.size() > MAX_FOLLOWERS) trimFollowers(f);
        }
        if (words.size() > MAX_WORDS || bigrams.size() > MAX_WORDS) prune();
        dirty = true;
    }

    /** Undoes one {@link #learn} call, used when an autocorrection is reverted. */
    public void unlearn(String prev, String word) {
        if (word == null) return;
        String k = word.toLowerCase(Locale.ROOT);
        Entry e = words.get(k);
        if (e != null) {
            boolean wasKnown = e.confirmed || e.count >= 2;
            e.count--;
            total = Math.max(0, total - 1);
            if (e.count <= 0 && !e.confirmed) words.remove(k);
            if (wasKnown && !(e.confirmed || e.count >= 2)) knownVersion++; // refresh Lexicon's extras
        }
        if (prev != null) {
            HashMap<String, Integer> f = bigrams.get(prev.toLowerCase(Locale.ROOT));
            if (f != null) {
                Integer c = f.get(k);
                if (c != null) {
                    if (c <= 1) f.remove(k);
                    else f.put(k, c - 1);
                }
            }
        }
        dirty = true;
    }

    /** Marks a word as one the person wants, so it is never autocorrected away. */
    public void confirm(String word) {
        String k = word.toLowerCase(Locale.ROOT);
        Entry e = words.get(k);
        if (e == null) {
            e = new Entry();
            e.display = word;
            words.put(k, e);
        }
        e.confirmed = true;
        corrections.remove(k); // keeping the word overrides an earlier fix of it
        knownVersion++;
        dirty = true;
    }

    /**
     * Remembers that this person changed {@code typo} into {@code fix} by hand, so autocorrect
     * makes that change by itself next time. The typo stops counting as a known word.
     */
    public void learnCorrection(String typo, String fix) {
        String t = typo.toLowerCase(Locale.ROOT), f = fix.toLowerCase(Locale.ROOT);
        if (t.equals(f)) return;
        if (corrections.size() >= MAX_CORRECTIONS && !corrections.containsKey(t)) {
            corrections.remove(corrections.keySet().iterator().next());
        }
        corrections.put(t, f);
        if (words.remove(t) != null) knownVersion++;
        dirty = true;
    }

    /** The fix this person taught for {@code lower}, or null. */
    public String correctionFor(String lower) {
        return corrections.get(lower);
    }

    public int correctionCount() {
        return corrections.size();
    }

    /** Adds a word to the "Words to keep" list: it's never autocorrected and is suggested as you type. */
    public void keep(String word) {
        String k = word.toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return;
        Entry e = words.get(k);
        if (e == null) {
            e = new Entry();
            words.put(k, e);
        }
        e.display = word;
        e.confirmed = true;
        e.kept = true;
        corrections.remove(k);
        knownVersion++;
        dirty = true;
    }

    /** Takes a word off the "Words to keep" list; it can be autocorrected again. */
    public void unkeep(String word) {
        String k = word.toLowerCase(Locale.ROOT);
        if (words.remove(k) != null) {
            knownVersion++;
            dirty = true;
        }
    }

    /** True if the person put this word on the "Words to keep" list. */
    public boolean isKept(String lower) {
        Entry e = words.get(lower);
        return e != null && e.kept;
    }

    /**
     * The words shown in settings as "Words to keep": the ones added there, plus words outside the
     * dictionary that the person kept some other way (e.g. by undoing an autocorrection). Sorted.
     */
    public ArrayList<String> keptWords(Dictionary dict) {
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : words.entrySet()) {
            Entry v = e.getValue();
            if (v.kept || (v.confirmed && dict.find(e.getKey()) < 0)) out.add(v.display);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Running average of where this person's taps land relative to each key's center. */
    public void learnTouch(char c, float dx, float dy) {
        int i = Dictionary.letterIndex(c);
        if (i < 0) return;
        if (Math.abs(dx) > 1.2f || Math.abs(dy) > 1.2f) return; // not a real hit on this key
        float a = offN[i] < 20 ? 1f / (offN[i] + 1) : 0.05f;
        offX[i] = clamp(offX[i] + a * (dx - offX[i]));
        offY[i] = clamp(offY[i] + a * (dy - offY[i]));
        offN[i]++;
        dirty = true;
    }

    // ---------------------------------------------------------------- queries

    public int count(String lower) {
        Entry e = words.get(lower);
        return e == null ? 0 : e.count;
    }

    /** A word counts as known once it was explicitly kept or used at least twice. */
    public boolean isKnown(String lower) {
        Entry e = words.get(lower);
        return e != null && (e.confirmed || e.count >= 2);
    }

    public String displayOf(String lower) {
        Entry e = words.get(lower);
        return e == null ? null : e.display;
    }

    /**
     * Combines the dictionary prior with this person's own usage. For words
     * outside the dictionary pass {@code Float.NEGATIVE_INFINITY} as base.
     */
    public float prior(String lower, float base) {
        Entry e = words.get(lower);
        if (e == null || e.count <= 0) return base;
        double user = 0.12 * e.count / (total + 50.0);
        double b = base == Float.NEGATIVE_INFINITY ? 0 : Math.exp(base);
        return (float) Math.log(b + user);
    }

    /** Extra log-score for a word given the previous word. */
    public float context(String prevLower, String lower) {
        if (prevLower == null) return 0f;
        HashMap<String, Integer> f = bigrams.get(prevLower);
        if (f == null) return 0f;
        Integer c = f.get(lower);
        if (c == null) return 0f;
        return (float) Math.min(4.0, 1.3 * Math.log(1 + c));
    }

    /** Most frequent followers of {@code prevLower}, as lowercase keys. */
    public ArrayList<String> predict(String prevLower, int n) {
        ArrayList<String> out = new ArrayList<>();
        if (prevLower == null) return out;
        HashMap<String, Integer> f = bigrams.get(prevLower);
        if (f == null) return out;
        ArrayList<Map.Entry<String, Integer>> list = new ArrayList<>(f.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        for (int i = 0; i < list.size() && out.size() < n; i++) {
            if (list.get(i).getValue() >= 2) out.add(list.get(i).getKey());
        }
        return out;
    }

    /** Words outside the dictionary that this person uses, for candidate generation. */
    public ArrayList<String> knownExtraWords(Dictionary dict) {
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : words.entrySet()) {
            Entry v = e.getValue();
            if ((v.confirmed || v.count >= 2) && dict.find(e.getKey()) < 0) out.add(e.getKey());
        }
        return out;
    }

    public float offsetX(char c) {
        int i = Dictionary.letterIndex(c);
        return i < 0 ? 0 : offX[i];
    }

    public float offsetY(char c) {
        int i = Dictionary.letterIndex(c);
        return i < 0 ? 0 : offY[i];
    }

    /** Words learned from typing (not counting the ones added to "Words to keep" by hand). */
    public int learnedWordCount() {
        int n = 0;
        for (Entry e : words.values()) if (!e.kept) n++;
        return n;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Changes whenever the set of known out-of-dictionary words may have changed. */
    public int knownVersion() {
        return knownVersion;
    }

    /** Forgets everything learned, except the "Words to keep" list. */
    public void clear() {
        knownVersion++;
        Iterator<Map.Entry<String, Entry>> it = words.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.kept) e.count = 0;
            else it.remove();
        }
        bigrams.clear();
        corrections.clear();
        total = 0;
        for (int i = 0; i < 26; i++) {
            offX[i] = 0;
            offY[i] = 0;
            offN[i] = 0;
        }
        dirty = true;
    }

    // ---------------------------------------------------------------- storage

    /** Plain tab-separated text; written to the app's private storage. */
    public String serialize() {
        StringBuilder sb = new StringBuilder(words.size() * 24);
        sb.append("V\t1\n");
        sb.append("T\t").append(total).append('\n');
        for (Map.Entry<String, Entry> e : words.entrySet()) {
            Entry v = e.getValue();
            sb.append("W\t").append(clean(v.display)).append('\t').append(v.count).append('\t')
                    .append(v.confirmed ? 1 : 0).append('\t').append(v.kept ? 1 : 0).append('\n');
        }
        for (Map.Entry<String, HashMap<String, Integer>> b : bigrams.entrySet()) {
            for (Map.Entry<String, Integer> f : b.getValue().entrySet()) {
                sb.append("B\t").append(clean(b.getKey())).append('\t').append(clean(f.getKey()))
                        .append('\t').append(f.getValue()).append('\n');
            }
        }
        for (Map.Entry<String, String> c : corrections.entrySet()) {
            sb.append("C\t").append(clean(c.getKey())).append('\t').append(clean(c.getValue())).append('\n');
        }
        for (int i = 0; i < 26; i++) {
            if (offN[i] == 0) continue;
            sb.append("O\t").append((char) ('a' + i)).append('\t').append(offX[i]).append('\t')
                    .append(offY[i]).append('\t').append(offN[i]).append('\n');
        }
        dirty = false;
        return sb.toString();
    }

    public void load(Reader reader) throws IOException {
        clear();
        BufferedReader r = new BufferedReader(reader);
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\t", -1);
            try {
                switch (p[0]) {
                    case "T":
                        total = Long.parseLong(p[1]);
                        break;
                    case "W": {
                        Entry e = new Entry();
                        e.display = p[1];
                        e.count = Integer.parseInt(p[2]);
                        e.confirmed = p[3].equals("1");
                        e.kept = p.length > 4 && p[4].equals("1");
                        words.put(p[1].toLowerCase(Locale.ROOT), e);
                        break;
                    }
                    case "B": {
                        HashMap<String, Integer> f = bigrams.get(p[1]);
                        if (f == null) {
                            f = new HashMap<>();
                            bigrams.put(p[1], f);
                        }
                        f.put(p[2], Integer.parseInt(p[3]));
                        break;
                    }
                    case "C":
                        corrections.put(p[1], p[2]);
                        break;
                    case "O": {
                        int i = Dictionary.letterIndex(p[1].charAt(0));
                        if (i >= 0) {
                            offX[i] = clamp(Float.parseFloat(p[2]));
                            offY[i] = clamp(Float.parseFloat(p[3]));
                            offN[i] = Integer.parseInt(p[4]);
                        }
                        break;
                    }
                    default:
                        break;
                }
            } catch (RuntimeException ignored) {
                // skip a damaged line rather than losing everything
            }
        }
        dirty = false;
    }

    // ---------------------------------------------------------------- helpers

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    private static float clamp(float v) {
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, v));
    }

    private void prune() {
        Iterator<Map.Entry<String, Entry>> it = words.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.count <= 1 && !e.confirmed) it.remove();
        }
        if (words.size() > MAX_WORDS * 9 / 10) {
            // still nearly full: drop the least-used words (never ones the person explicitly kept)
            ArrayList<Map.Entry<String, Entry>> list = new ArrayList<>(words.entrySet());
            Collections.sort(list, new Comparator<Map.Entry<String, Entry>>() {
                @Override
                public int compare(Map.Entry<String, Entry> x, Map.Entry<String, Entry> y) {
                    return Integer.compare(x.getValue().count, y.getValue().count);
                }
            });
            int excess = words.size() - MAX_WORDS * 9 / 10;
            for (Map.Entry<String, Entry> e : list) {
                if (excess <= 0) break;
                if (e.getValue().confirmed) continue;
                words.remove(e.getKey());
                excess--;
            }
        }
        // next-word data for words that are no longer kept, so the saved file can't grow without limit
        Iterator<Map.Entry<String, HashMap<String, Integer>>> b = bigrams.entrySet().iterator();
        while (b.hasNext()) {
            Map.Entry<String, HashMap<String, Integer>> e = b.next();
            if (!words.containsKey(e.getKey())) b.remove();
        }
        knownVersion++;
    }

    private static void trimFollowers(HashMap<String, Integer> f) {
        Iterator<Map.Entry<String, Integer>> it = f.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= 1) it.remove();
        }
    }
}
