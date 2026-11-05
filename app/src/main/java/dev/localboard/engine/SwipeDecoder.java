package dev.localboard.engine;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Decodes a swipe (gesture) path into words.
 *
 * For every word whose first and last letters are near the start and end of
 * the path, two costs are computed:
 *  - letter cost: the path must pass near each of the word's keys, in order
 *    (an order-preserving alignment of letters to path points);
 *  - shape cost: the path, resampled to fixed points, compared point by point
 *    with the ideal path that connects the word's key centers.
 * The final score adds the word's prior (dictionary frequency, personal usage
 * and previous-word context).
 */
public final class SwipeDecoder {
    static final int FINE = 64;          // points used for the letter alignment
    static final int SHAPE = 32;         // points used for the shape comparison
    static final float START_RADIUS = 1.4f;
    static final float END_RADIUS = 1.6f;
    static final float TWO_SIGMA_LETTER_SQ = 2f * 0.55f * 0.55f;
    static final float TWO_SIGMA_SHAPE_SQ = 2f * 0.6f * 0.6f;
    public static float shapeWeight = 12f;
    public static float endWeight = 2f;
    public static float priorWeight = 1.0f;

    private final Lexicon lex;
    private KeyGeometry geo;
    public boolean blockOffensive = true;

    private final float[] fx = new float[FINE], fy = new float[FINE];
    private final float[] sx = new float[SHAPE], sy = new float[SHAPE];
    private final float[] dpPrev = new float[FINE], dpCur = new float[FINE];
    private final char[] seq = new char[80];
    private final HashMap<String, float[]> shapeCache = new HashMap<>();
    private float pathLength;

    public SwipeDecoder(Lexicon lex, KeyGeometry geo) {
        this.lex = lex;
        this.geo = geo;
    }

    public void setGeometry(KeyGeometry g) {
        geo = g;
        shapeCache.clear();
    }

    /** Path points in key widths. Returns up to {@code max} words, best first. */
    public ArrayList<Candidate> decode(float[] xs, float[] ys, int count, String prev, int max) {
        ArrayList<Candidate> none = new ArrayList<>();
        if (count < 2) return none;
        pathLength = resample(xs, ys, count, FINE, fx, fy);
        if (pathLength < 0.6f) return none;
        resample(xs, ys, count, SHAPE, sx, sy);

        boolean[] endOk = new boolean[26];
        boolean any = false;
        for (int l = 0; l < 26; l++) {
            char c = (char) ('a' + l);
            if (!geo.has(c)) continue;
            if (dist(fx[FINE - 1], fy[FINE - 1], c) < END_RADIUS) {
                endOk[l] = true;
                any = true;
            }
        }
        if (!any) return none;

        TopK top = new TopK(Math.max(max, 1));
        Dictionary dict = lex.dict;
        for (int f = 0; f < 26; f++) {
            char c = (char) ('a' + f);
            if (!geo.has(c) || dist(fx[0], fy[0], c) >= START_RADIUS) continue;
            for (int id : dict.byFirst[f]) {
                if (blockOffensive && dict.offensive[id]) continue;
                consider(dict.key[id], id, endOk, prev, top);
            }
            for (String k : lex.extras(f)) consider(k, -1, endOk, prev, top);
        }
        if (shapeCache.size() > 30000) shapeCache.clear();
        return top.toList();
    }

    private void consider(String key, int id, boolean[] endOk, String prev, TopK top) {
        int m = key.length();
        if (m < 2 || m > 40) return;
        // last letter of the word (ignoring apostrophes) must be near the end of the path
        int last = m - 1;
        while (last > 0 && Dictionary.letterIndex(key.charAt(last)) < 0) last--;
        int li = Dictionary.letterIndex(key.charAt(last));
        if (li < 0 || !endOk[li]) return;

        // collapse to the sequence of distinct consecutive letter keys
        int L = 0;
        for (int j = 0; j < m; j++) {
            char c = key.charAt(j);
            if (Dictionary.letterIndex(c) < 0 || !geo.has(c)) continue;
            if (L > 0 && seq[L - 1] == c) continue;
            seq[L++] = c;
        }
        if (L == 0) return;

        float prior = lex.prior(key, id);
        if (Float.isInfinite(prior)) return;
        float base = priorWeight * prior + lex.user.context(prev, key);
        float bound = top.threshold();
        if (base < bound) return; // even a perfect match could not make the list

        float letterCost = alignLetters(L, bound - base);
        if (Float.isInfinite(letterCost)) return;
        if (base - letterCost < bound) return;

        float[] ideal = shapeCache.get(key);
        if (ideal == null) {
            ideal = idealShape(L);
            shapeCache.put(key, ideal);
        }
        float shape = 0f;
        for (int k = 0; k < SHAPE; k++) {
            float dx = sx[k] - ideal[k], dy = sy[k] - ideal[SHAPE + k];
            shape += dx * dx + dy * dy;
        }
        shape = shape / TWO_SIGMA_SHAPE_SQ / SHAPE * shapeWeight;

        // penalize paths much longer or shorter than the word's ideal path
        float idealLen = ideal[2 * SHAPE];
        float lr = (float) Math.log((pathLength + 0.5f) / (idealLen + 0.5f));
        float lengthPenalty = 3f * lr * lr;

        float cost = letterCost + shape + lengthPenalty;
        boolean off = id >= 0 && lex.dict.offensive[id];
        top.offer(new Candidate(key, lex.display(key, id), base - cost, cost, off));
    }

    /**
     * Order-preserving alignment of the word's letters to the fine path. The
     * first letter is pinned to the first point and the last letter to the
     * last point. Returns infinity once the cost exceeds {@code budget}.
     */
    private float alignLetters(int L, float budget) {
        float limit = Float.isInfinite(budget) ? Float.MAX_VALUE : -budget;
        // first letter at point 0
        float first = endWeight * d2(fx[0], fy[0], seq[0]) / TWO_SIGMA_LETTER_SQ;
        if (L == 1) {
            float lastC = endWeight * d2(fx[FINE - 1], fy[FINE - 1], seq[0]) / TWO_SIGMA_LETTER_SQ;
            return first + lastC;
        }
        // dpPrev[p] = best cost with letter l placed at point <= p
        for (int p = 0; p < FINE; p++) dpPrev[p] = first;
        for (int l = 1; l < L; l++) {
            char c = seq[l];
            float run = Float.POSITIVE_INFINITY;
            float rowMin = Float.POSITIVE_INFINITY;
            for (int p = 0; p < FINE; p++) {
                float here;
                if (l == L - 1 && p != FINE - 1) {
                    here = Float.POSITIVE_INFINITY;
                } else {
                    float before = p > 0 ? dpPrev[p - 1] : Float.POSITIVE_INFINITY;
                    float w = l == L - 1 ? endWeight : 1f;
                    here = before + w * d2(fx[p], fy[p], c) / TWO_SIGMA_LETTER_SQ;
                }
                if (here < run) run = here;
                dpCur[p] = run;
                if (run < rowMin) rowMin = run;
            }
            if (rowMin > limit) return Float.POSITIVE_INFINITY;
            System.arraycopy(dpCur, 0, dpPrev, 0, FINE);
        }
        float total = dpPrev[FINE - 1];
        return total > limit ? Float.POSITIVE_INFINITY : total;
    }

    /** Ideal path through the key centers: x[SHAPE], y[SHAPE], then its length. */
    private float[] idealShape(int L) {
        float[] px = new float[L], py = new float[L];
        for (int i = 0; i < L; i++) {
            px[i] = geo.x(seq[i]);
            py[i] = geo.y(seq[i]);
        }
        float[] out = new float[2 * SHAPE + 1];
        float[] ox = new float[SHAPE], oy = new float[SHAPE];
        float len = resample(px, py, L, SHAPE, ox, oy);
        System.arraycopy(ox, 0, out, 0, SHAPE);
        System.arraycopy(oy, 0, out, SHAPE, SHAPE);
        out[2 * SHAPE] = len;
        return out;
    }

    /** Resamples a polyline to {@code n} points equally spaced along its length; returns the length. */
    static float resample(float[] xs, float[] ys, int count, int n, float[] ox, float[] oy) {
        float total = 0f;
        for (int i = 1; i < count; i++) total += (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        if (count == 1 || total <= 1e-6f) {
            for (int k = 0; k < n; k++) {
                ox[k] = xs[0];
                oy[k] = ys[0];
            }
            return 0f;
        }
        float step = total / (n - 1);
        ox[0] = xs[0];
        oy[0] = ys[0];
        int k = 1;
        float acc = 0f;
        int i = 1;
        float cx = xs[0], cy = ys[0];
        while (k < n - 1 && i < count) {
            float seg = (float) Math.hypot(xs[i] - cx, ys[i] - cy);
            if (acc + seg >= step && seg > 0) {
                float t = (step - acc) / seg;
                cx = cx + t * (xs[i] - cx);
                cy = cy + t * (ys[i] - cy);
                ox[k] = cx;
                oy[k] = cy;
                k++;
                acc = 0f;
            } else {
                acc += seg;
                cx = xs[i];
                cy = ys[i];
                i++;
            }
        }
        while (k < n) {
            ox[k] = xs[count - 1];
            oy[k] = ys[count - 1];
            k++;
        }
        return total;
    }

    private float d2(float x, float y, char c) {
        float dx = x - geo.x(c), dy = y - geo.y(c);
        return dx * dx + dy * dy;
    }

    private float dist(float x, float y, char c) {
        return (float) Math.sqrt(d2(x, y, c));
    }
}
