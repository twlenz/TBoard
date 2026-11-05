package dev.localboard.engine;

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
    }

    private final HashMap<String, Entry> words = new HashMap<>();
    private final HashMap<String, HashMap<String, Integer>> bigrams = new HashMap<>();
    private long total;
    private final float[] offX = new float[26];
    private final float[] offY = new float[26];
    private final int[] offN = new int[26];
    private boolean dirty;
    private int knownVersion;

    private static final int MAX_WORDS = 40000;
    private static final int MAX_FOLLOWERS = 60;
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
        if (words.size() > MAX_WORDS) prune();
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
        knownVersion++;
        dirty = true;
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

    public int learnedWordCount() {
        return words.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Changes whenever the set of known out-of-dictionary words may have changed. */
    public int knownVersion() {
        return knownVersion;
    }

    public void clear() {
        knownVersion++;
        words.clear();
        bigrams.clear();
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
                    .append(v.confirmed ? 1 : 0).append('\n');
        }
        for (Map.Entry<String, HashMap<String, Integer>> b : bigrams.entrySet()) {
            for (Map.Entry<String, Integer> f : b.getValue().entrySet()) {
                sb.append("B\t").append(clean(b.getKey())).append('\t').append(clean(f.getKey()))
                        .append('\t').append(f.getValue()).append('\n');
            }
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
    }

    private static void trimFollowers(HashMap<String, Integer> f) {
        Iterator<Map.Entry<String, Integer>> it = f.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= 1) it.remove();
        }
    }
}
