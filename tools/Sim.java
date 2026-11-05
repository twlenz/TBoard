/**
 * Accuracy and speed simulation for the engine: noisy taps (with typos) and noisy swipes over
 * words sampled from the dictionary. Usage: java Sim path/to/en_words.tsv [tapNoise] [shapeW endW priorW]
 */
import dev.tboard.engine.*;
import dev.tboard.engine.Dictionary;
import java.io.*;
import java.util.*;

public class Sim {
    static Random rnd = new Random(42);
    static KeyGeometry geo = KeyGeometry.qwerty(1.5f);

    public static void main(String[] a) throws Exception {
        Dictionary d = Dictionary.load(new FileInputStream(a[0]));
        UserModel u = new UserModel();
        Lexicon lex = new Lexicon(d, u);
        TapDecoder tap = new TapDecoder(lex, geo);
        SwipeDecoder sw = new SwipeDecoder(lex, geo);
        if (a.length > 2) { SwipeDecoder.shapeWeight = Float.parseFloat(a[2]); SwipeDecoder.endWeight = Float.parseFloat(a[3]); SwipeDecoder.priorWeight = Float.parseFloat(a[4]); }
        float noise = a.length > 1 ? Float.parseFloat(a[1]) : 0.3f;
        // test words: sample by frequency rank from top 8000, length>=2, letters only
        List<String> words = new ArrayList<>();
        while (words.size() < 400) {
            int id = (int) Math.min(7999, Math.abs(rnd.nextGaussian()) * 2500);
            String k = d.key[id];
            if (k.length() >= 2 && k.matches("[a-z]+")) words.add(k);
        }
        // ---- taps, clean (only spatial noise)
        int ok = 0, typoOk = 0, typoN = 0, falseCorr = 0; long t0 = System.nanoTime(); int calls = 0;
        List<String> fails = new ArrayList<>();
        for (String w : words) {
            String typedTarget = w;
            boolean typo = rnd.nextDouble() < 0.35 && w.length() >= 4;
            if (typo) typedTarget = makeTypo(w);
            String typed = typeIt(typedTarget, noise);
            float[][] pts = lastPts;
            TapDecoder.Result r = tap.decode(typed, pts[0], pts[1], null); calls++;
            String fin = r.correction != null ? r.correction.key : typed;
            if (typo) { typoN++; if (fin.equals(w)) typoOk++; else if (fails.size()<25) fails.add(typedTarget+"->"+typed+"=>"+fin+" want "+w); }
            else { if (fin.equals(w)) ok++; else { if (typed.equals(w)) falseCorr++; if (fails.size()<25) fails.add(typed+"=>"+fin+" want "+w); } }
        }
        long t1 = System.nanoTime();
        int clean = words.size() - typoN;
        System.out.printf("TAP noise=%.2f clean words correct %d/%d (false corrections of correctly-hit words %d), typo words fixed %d/%d, %.1f ms/decode%n",
            noise, ok, clean, falseCorr, typoOk, typoN, (t1 - t0) / 1e6 / calls);
        for (String f : fails) System.out.println("  " + f);

        // ---- swipes
        int top1 = 0, top3 = 0; t0 = System.nanoTime(); fails.clear(); int sn = 0;
        for (String w : words) {
            if (w.length() < 2) continue; sn++;
            float[][] p = swipePath(w);
            ArrayList<Candidate> res = sw.decode(p[0], p[1], p[0].length, null, 5);
            if (!res.isEmpty() && res.get(0).key.equals(w)) top1++;
            else if (fails.size() < 25) fails.add(w + " => " + (res.isEmpty() ? "-" : res.subList(0, Math.min(3,res.size())).toString()));
            for (int i = 0; i < Math.min(3, res.size()); i++) if (res.get(i).key.equals(w)) { top3++; break; }
        }
        t1 = System.nanoTime();
        System.out.printf("SWIPE top1 %d/%d top3 %d/%d, %.1f ms/decode%n", top1, sn, top3, sn, (t1 - t0) / 1e6 / sn);
        for (String f : fails) System.out.println("  " + f);

        // ---- learning: a new word gets typed twice, then must survive autocorrect
        String nw = "zorbly";
        for (int i = 0; i < 2; i++) u.learn(null, nw, false);
        String typed = typeIt(nw, noise);
        TapDecoder.Result r = tap.decode(typed, lastPts[0], lastPts[1], null);
        System.out.println("learned word kept: " + (r.correction == null) + " ; suggestions: " + r.words);
        // round-trip storage
        String s = u.serialize(); UserModel u2 = new UserModel(); u2.load(new StringReader(s));
        System.out.println("storage round trip known=" + u2.isKnown(nw));
    }

    static float[][] lastPts;
    static String typeIt(String w, float noise) {
        float[] xs = new float[w.length()], ys = new float[w.length()];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            float x = geo.x(c) + (float) rnd.nextGaussian() * noise;
            float y = geo.y(c) + (float) rnd.nextGaussian() * noise * 1.2f;
            xs[i] = x; ys[i] = y;
            sb.append(hit(x, y));
        }
        lastPts = new float[][]{xs, ys};
        return sb.toString();
    }
    // which key a touch lands on (rows 1.5 tall, row offsets as in layout)
    static char hit(float x, float y) {
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] off = {0f, 0.5f, 1.5f};
        int r = (int) Math.floor(y / 1.5f); r = Math.max(0, Math.min(2, r));
        int i = (int) Math.floor(x - off[r]); i = Math.max(0, Math.min(rows[r].length() - 1, i));
        return rows[r].charAt(i);
    }
    static String makeTypo(String w) {
        int k = rnd.nextInt(3), i = rnd.nextInt(w.length() - 1);
        if (k == 0) return w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2); // swap
        if (k == 1) return w.substring(0, i + 1) + w.substring(i + 2); // drop a letter (keep first)
        return w.substring(0, i + 1) + w.charAt(i) + w.substring(i + 1); // doubled letter
    }
    static float[][] swipePath(String w) {
        List<float[]> corners = new ArrayList<>();
        char prev = 0;
        for (char c : w.toCharArray()) { if (c == prev) continue; prev = c;
            corners.add(new float[]{geo.x(c) + (float) rnd.nextGaussian() * 0.25f, geo.y(c) + (float) rnd.nextGaussian() * 0.3f}); }
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        xs.add(corners.get(0)[0]); ys.add(corners.get(0)[1]);
        for (int i = 1; i < corners.size(); i++) {
            float[] p = corners.get(i - 1), q = corners.get(i);
            int steps = Math.max(2, (int) (Math.hypot(q[0] - p[0], q[1] - p[1]) / 0.15));
            for (int s = 1; s <= steps; s++) { float t = s / (float) steps;
                xs.add(p[0] + t * (q[0] - p[0]) + (float) rnd.nextGaussian() * 0.03f);
                ys.add(p[1] + t * (q[1] - p[1]) + (float) rnd.nextGaussian() * 0.03f); }
        }
        if (corners.size() == 1) { xs.add(xs.get(0) + 0.05f); ys.add(ys.get(0)); }
        float[] ax = new float[xs.size()], ay = new float[ys.size()];
        for (int i = 0; i < ax.length; i++) { ax[i] = xs.get(i); ay[i] = ys.get(i); }
        return new float[][]{ax, ay};
    }
}
