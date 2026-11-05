package dev.localboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Finds emoji by their Unicode/CLDR English names and keywords ("heart", "cry", "thumbs up"). */
public final class EmojiSearch {
    public interface Filter {
        boolean shows(String emoji);
    }

    private final String[] emoji;
    private final String[][] words;

    public EmojiSearch() {
        ArrayList<String> es = new ArrayList<>();
        ArrayList<String[]> ws = new ArrayList<>();
        for (int c = 0; c < EmojiData.CATEGORIES.length; c++) {
            String[] e = EmojiData.CATEGORIES[c].split(" ");
            String[] k = EmojiData.KEYWORDS[c].split("\\|", -1);
            for (int i = 0; i < e.length && i < k.length; i++) {
                es.add(e[i]);
                ws.add(k[i].split(" "));
            }
        }
        emoji = es.toArray(new String[0]);
        words = ws.toArray(new String[0][]);
    }

    /**
     * Emoji where every word of the query starts one of its keywords, best first: whole-word matches
     * beat prefix matches, matches in the name beat other keywords, then Unicode's order.
     */
    public ArrayList<String> search(String query, int max, Filter filter) {
        ArrayList<String> out = new ArrayList<>();
        String[] q = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (q.length == 0 || q[0].isEmpty()) return out;
        long[] hits = new long[emoji.length];
        int nHits = 0;
        for (int i = 0; i < emoji.length; i++) {
            int score = 0;
            boolean all = true;
            for (String t : q) {
                int best = 0;
                String[] w = words[i];
                for (int j = 0; j < w.length; j++) {
                    int s = w[j].equals(t) ? 4 : w[j].startsWith(t) ? 2 : 0;
                    if (s > 0 && j < 3) s += 1; // the first words are the emoji's name
                    if (s > best) best = s;
                }
                if (best == 0) {
                    all = false;
                    break;
                }
                score += best;
            }
            if (all) hits[nHits++] = ((long) (1000 - score) << 32) | i; // sorts by score, then order
        }
        Arrays.sort(hits, 0, nHits);
        for (int h = 0; h < nHits && out.size() < max; h++) {
            String e = emoji[(int) (hits[h] & 0xFFFFFFFFL)];
            if (filter == null || filter.shows(e)) out.add(e);
        }
        return out;
    }
}
