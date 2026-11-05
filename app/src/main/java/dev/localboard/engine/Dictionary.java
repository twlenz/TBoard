package dev.localboard.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * The bundled English word list. Each line of the asset is
 * {@code displayForm \t zipfFrequency \t offensiveFlag}.
 * Words are matched by their lowercase key; the display form keeps
 * capitals for words like "I", "Monday" or "NASA".
 */
public final class Dictionary {
    public final String[] display;
    public final String[] key;
    public final float[] logPrior;
    public final boolean[] offensive;
    /** Word ids grouped by the first letter of their key, a..z. */
    final int[][] byFirst;
    private final HashMap<String, Integer> index;

    private static final double LN10 = Math.log(10);

    /** Zipf frequency (log10 of occurrences per billion words) to natural-log probability. */
    public static float zipfToLog(float zipf) {
        return (float) ((zipf - 9.0) * LN10);
    }

    private Dictionary(ArrayList<String> disp, ArrayList<Float> zipf, ArrayList<Boolean> off) {
        int n = disp.size();
        display = new String[n];
        key = new String[n];
        logPrior = new float[n];
        offensive = new boolean[n];
        index = new HashMap<>(n * 2);
        int[] firstCounts = new int[26];
        for (int i = 0; i < n; i++) {
            display[i] = disp.get(i);
            key[i] = display[i].toLowerCase(Locale.ROOT);
            logPrior[i] = zipfToLog(zipf.get(i));
            offensive[i] = off.get(i);
            if (!index.containsKey(key[i])) index.put(key[i], i);
            int f = letterIndex(key[i].charAt(0));
            if (f >= 0) firstCounts[f]++;
        }
        byFirst = new int[26][];
        for (int f = 0; f < 26; f++) byFirst[f] = new int[firstCounts[f]];
        int[] fill = new int[26];
        for (int i = 0; i < n; i++) {
            int f = letterIndex(key[i].charAt(0));
            if (f >= 0) byFirst[f][fill[f]++] = i;
        }
    }

    public static Dictionary load(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        ArrayList<String> disp = new ArrayList<>(70000);
        ArrayList<Float> zipf = new ArrayList<>(70000);
        ArrayList<Boolean> off = new ArrayList<>(70000);
        String line;
        while ((line = r.readLine()) != null) {
            int t1 = line.indexOf('\t');
            if (t1 <= 0) continue;
            int t2 = line.indexOf('\t', t1 + 1);
            String w = line.substring(0, t1);
            float z;
            boolean o = false;
            try {
                z = Float.parseFloat(t2 < 0 ? line.substring(t1 + 1) : line.substring(t1 + 1, t2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (t2 > 0) o = line.substring(t2 + 1).trim().equals("1");
            disp.add(w);
            zipf.add(z);
            off.add(o);
        }
        r.close();
        return new Dictionary(disp, zipf, off);
    }

    /** Returns the word id for a lowercase key, or -1. */
    public int find(String lower) {
        Integer i = index.get(lower);
        return i == null ? -1 : i;
    }

    public int size() {
        return key.length;
    }

    public static int letterIndex(char c) {
        if (c >= 'a' && c <= 'z') return c - 'a';
        return -1;
    }
}
