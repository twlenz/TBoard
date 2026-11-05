package dev.tboard.engine;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Autocorrect and suggestions for tapped words.
 *
 * Each candidate word is scored as log P(word) + context - cost, where cost is
 * a weighted edit distance between the taps and the word. Substituting a letter
 * costs more the farther the tap landed from that letter's key (a Gaussian
 * touch model, centered on where this person usually hits each key), so a tap
 * on the edge between two keys is cheap to reinterpret while a tap across the
 * keyboard is not.
 */
public final class TapDecoder {
    static final float TWO_SIGMA_SQ = 2f * 0.5f * 0.5f;
    static final float FAR = 9f;
    static final float INSERT = 4.0f;        // typed a letter the word doesn't have
    static final float INSERT_DOUBLE = 1.5f; // tapped the same key twice
    static final float DELETE = 4.0f;        // skipped a letter the word has
    static final float DELETE_DOUBLE = 1.5f; // typed "leter" for "letter"
    static final float DELETE_MARK = 0.1f;   // typed "dont" for "don't"
    static final float TRANSPOSE = 1.5f;     // typed "teh" for "the"
    static final float COMPLETE_PER_CHAR = 0.25f;
    static final float VALID_MARGIN = 5.0f;  // how much better a correction must be when the typed word is real

    private final Lexicon lex;
    private KeyGeometry geo;
    public boolean blockOffensive = false;

    private final float[][] sub = new float[64][26];
    private final char[] typed = new char[64];
    private float[] r0 = new float[80], r1 = new float[80], r2 = new float[80];
    private float lastPrefixCost;

    public static final class Result {
        /** Whole-word matches, best first; may include the typed word itself. */
        public ArrayList<Candidate> words = new ArrayList<>();
        /** Longer words the typing could be the start of, best first. */
        public ArrayList<Candidate> completions = new ArrayList<>();
        /** The word autocorrect would put in on space, or null to keep what was typed. */
        public Candidate correction;
        public boolean typedKnown;
    }

    public TapDecoder(Lexicon lex, KeyGeometry geo) {
        this.lex = lex;
        this.geo = geo;
    }

    public void setGeometry(KeyGeometry g) {
        geo = g;
    }

    /**
     * @param word the characters typed so far, as shown
     * @param xs   tap x per character in key widths, or NaN if the character didn't come from a tap
     * @param ys   tap y per character in key widths, or NaN
     * @param prev previous word, lowercase, or null
     */
    public Result decode(String word, float[] xs, float[] ys, String prev) {
        Result res = new Result();
        String lower = word.toLowerCase(Locale.ROOT);
        int n = Math.min(lower.length(), 60);
        if (n == 0) return res;
        res.typedKnown = lex.isKnown(lower);

        for (int i = 0; i < n; i++) {
            char t = lower.charAt(i);
            typed[i] = t;
            boolean hasPoint = i < xs.length && !Float.isNaN(xs[i]);
            for (int l = 0; l < 26; l++) {
                char c = (char) ('a' + l);
                float cost;
                if (hasPoint && geo.has(c)) {
                    float dx = xs[i] - (geo.x(c) + lex.user.offsetX(c));
                    float dy = ys[i] - (geo.y(c) + lex.user.offsetY(c));
                    cost = Math.min(FAR, (dx * dx + dy * dy) / TWO_SIGMA_SQ);
                } else {
                    cost = t == c ? 0f : FAR;
                }
                sub[i][l] = cost;
            }
        }

        TopK full = new TopK(8);
        TopK comp = new TopK(8);
        float limit = 4f + 2.0f * n;
        Dictionary dict = lex.dict;

        for (int f = 0; f < 26; f++) {
            // the word may start with the first tap's letter or a neighbor, or with the
            // second tap's letter (first two letters swapped, as in "otols")
            boolean firstOk = sub[0][f] < 3.0f || typed[0] == (char) ('a' + f)
                    || (n >= 3 && sub[1][f] < 1.0f);
            if (!firstOk) continue;
            int[] ids = dict.byFirst[f];
            for (int id : ids) {
                if (blockOffensive && dict.offensive[id]) continue;
                score(dict.key[id], id, n, limit, prev, full, comp);
            }
            for (String k : lex.extras(f)) score(k, -1, n, limit, prev, full, comp);
        }
        // the typed word is always a candidate, even if blocked or its first tap was odd
        if (res.typedKnown) score(lower, dict.find(lower), n, Float.MAX_VALUE, prev, full, null);

        res.words = full.toList();
        res.completions = comp.toList();

        Candidate typedCand = null;
        Candidate best = null;
        for (Candidate c : res.words) {
            if (c.key.equals(lower)) {
                if (typedCand == null) typedCand = c;
            } else if (best == null && !(blockOffensive && lex.isOffensive(c.key))) {
                best = c;
            }
        }
        if (best != null && n >= 2) {
            if (res.typedKnown) {
                float ts = typedCand != null ? typedCand.score : best.score;
                if (best.score > ts + VALID_MARGIN) res.correction = best;
            } else if (best.cost <= 2.0f + 1.1f * n) {
                res.correction = best;
            }
        }

        // a fix this person made by hand before wins over everything else
        String fix = n >= 2 ? lex.user.correctionFor(lower) : null;
        if (fix != null && lex.isKnown(fix) && !(blockOffensive && lex.isOffensive(fix))) {
            int id = dict.find(fix);
            Candidate c = new Candidate(fix, lex.display(fix, id), 0f, 0f, id >= 0 && dict.offensive[id]);
            for (int i = res.words.size() - 1; i >= 0; i--) {
                if (res.words.get(i).key.equals(fix)) res.words.remove(i);
            }
            res.words.add(0, c);
            res.correction = c;
        }
        return res;
    }

    private void score(String key, int id, int n, float limit, String prev, TopK full, TopK comp) {
        int m = key.length();
        if (m < n - 3 || m > n + 12 || m > 70) return;
        float prior = lex.prior(key, id);
        if (Float.isInfinite(prior)) return;
        float base = prior + lex.user.context(prev, key);
        boolean force = limit == Float.MAX_VALUE;
        if (!force) {
            // the word only matters if it can still beat the worst entry of a list it could join
            float bound = Float.NEGATIVE_INFINITY;
            if (m <= n + 3) bound = Math.max(bound, base - full.threshold());
            if (comp != null && m > n) bound = Math.max(bound, base - comp.threshold());
            if (bound <= 0f) return;
            if (bound < limit) limit = bound;
        }
        float cost = align(key, n, limit);
        float prefix = lastPrefixCost;
        if (Float.isInfinite(cost) && Float.isInfinite(prefix)) return;
        boolean off = id >= 0 && lex.dict.offensive[id];
        String disp = lex.display(key, id);
        if (!Float.isInfinite(cost) && m <= n + 3) {
            full.offer(new Candidate(key, disp, base - cost, cost, off));
        }
        if (comp != null && m > n && !Float.isInfinite(prefix)) {
            comp.offer(new Candidate(key, disp, base - prefix, prefix, off));
        }
    }

    /**
     * Weighted Damerau-Levenshtein alignment of the taps against {@code key}.
     * Returns the whole-word cost and leaves the best prefix (completion) cost
     * in {@link #lastPrefixCost}. Both are infinite if the word was abandoned
     * early because it could not come in under {@code limit}.
     */
    private float align(String key, int n, float limit) {
        int m = key.length();
        if (r0.length < m + 1) {
            r0 = new float[m + 1];
            r1 = new float[m + 1];
            r2 = new float[m + 1];
        }
        float[] pp = r0, p = r1, cur = r2;
        p[0] = 0f;
        for (int j = 1; j <= m; j++) p[j] = p[j - 1] + deleteCost(key, j);
        for (int i = 1; i <= n; i++) {
            cur[0] = i * INSERT;
            float rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                float best = p[j - 1] + subCost(i, key.charAt(j - 1));
                float ins = p[j] + (i >= 2 && typed[i - 1] == typed[i - 2] ? INSERT_DOUBLE : INSERT);
                if (ins < best) best = ins;
                float del = cur[j - 1] + deleteCost(key, j);
                if (del < best) best = del;
                if (i >= 2 && j >= 2) {
                    char a = key.charAt(j - 2), b = key.charAt(j - 1);
                    if (a != b) {
                        float tr = pp[j - 2] + TRANSPOSE + subCost(i, a) + subCost(i - 1, b);
                        if (tr < best) best = tr;
                    }
                }
                cur[j] = best;
                if (best < rowMin) rowMin = best;
            }
            if (rowMin > limit) {
                lastPrefixCost = Float.POSITIVE_INFINITY;
                return Float.POSITIVE_INFINITY;
            }
            float[] t = pp;
            pp = p;
            p = cur;
            cur = t;
        }
        // p now holds row n
        float prefix = Float.POSITIVE_INFINITY;
        for (int j = Math.max(1, n - 1); j <= m; j++) {
            float c = p[j] + (m - j) * COMPLETE_PER_CHAR;
            if (c < prefix) prefix = c;
        }
        lastPrefixCost = prefix;
        float whole = p[m];
        return whole > limit ? Float.POSITIVE_INFINITY : whole;
    }

    private float subCost(int i, char c) {
        int l = Dictionary.letterIndex(c);
        if (l >= 0) return sub[i - 1][l];
        return typed[i - 1] == c ? 0f : FAR;
    }

    private static float deleteCost(String key, int j) {
        char c = key.charAt(j - 1);
        if (c < 'a' || c > 'z') return DELETE_MARK;
        if (j >= 2 && key.charAt(j - 2) == c) return DELETE_DOUBLE;
        return DELETE;
    }
}
