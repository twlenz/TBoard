package dev.tboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Finds emoji by their Unicode/CLDR English names and keywords ("heart", "cry", "thumbs up"). */
public final class EmojiSearch {
    public interface Filter {
        boolean shows(String emoji);
    }

    /** Unicode's ten most-used emoji (Unicode Consortium emoji frequency data, 2021), ranked up on ties. */
    private static final String POPULAR = "\ud83d\ude02 \u2764\ufe0f \ud83e\udd23 \ud83d\udc4d \ud83d\ude2d "
            + "\ud83d\ude4f \ud83d\ude18 \ud83e\udd70 \ud83d\ude0d \ud83d\ude0a";

    private final String[] emoji;
    private final String[][] words;
    private final int[] nameLength;
    private final java.util.HashSet<String> popular =
            new java.util.HashSet<>(Arrays.asList(POPULAR.split(" ")));

    public EmojiSearch() {
        ArrayList<String> es = new ArrayList<>();
        ArrayList<String[]> ws = new ArrayList<>();
        ArrayList<Integer> nl = new ArrayList<>();
        for (int c = 0; c < EmojiData.CATEGORIES.length; c++) {
            String[] e = EmojiData.CATEGORIES[c].split(" ");
            String[] k = EmojiData.KEYWORDS[c].split("\\|", -1);
            for (int i = 0; i < e.length && i < k.length; i++) {
                String[] w = k[i].split(" ");
                int name = Arrays.asList(w).indexOf("~");
                es.add(e[i]);
                ws.add(w);
                nl.add(name < 0 ? w.length : name);
            }
        }
        emoji = es.toArray(new String[0]);
        words = ws.toArray(new String[0][]);
        nameLength = new int[nl.size()];
        for (int i = 0; i < nameLength.length; i++) nameLength[i] = nl.get(i);
    }

    /**
     * Emoji where every word of the query starts one of its words, best first. Matching the emoji's
     * name beats matching another keyword, a whole word beats a prefix, then recently used and very
     * common emoji, then shorter names, then Unicode's order.
     */
    public ArrayList<String> search(String query, int max, Filter filter, java.util.List<String> recent) {
        ArrayList<String> out = new ArrayList<>();
        String[] q = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (q.length == 0 || q[0].isEmpty()) return out;
        long[] hits = new long[emoji.length];
        int nHits = 0;
        for (int i = 0; i < emoji.length; i++) {
            int score = 0;
            boolean all = true;
            String[] w = words[i];
            for (String t : q) {
                int best = 0;
                for (int j = 0; j < w.length; j++) {
                    boolean inName = j < nameLength[i];
                    int s = w[j].equals(t) ? (inName ? 8 : 4) : w[j].startsWith(t) ? (inName ? 5 : 2) : 0;
                    if (s > best) best = s;
                }
                if (best == 0) {
                    all = false;
                    break;
                }
                score += best;
            }
            if (!all) continue;
            score *= 4;
            if (recent != null && recent.contains(emoji[i])) score += 3;
            if (popular.contains(emoji[i])) score += 2;
            score -= Math.min(3, nameLength[i] - 1); // "red heart" before "heart with arrow"
            hits[nHits++] = ((long) (100000 - score) << 32) | i; // sorts by score, then Unicode order
        }
        Arrays.sort(hits, 0, nHits);
        for (int h = 0; h < nHits && out.size() < max; h++) {
            String e = emoji[(int) (hits[h] & 0xFFFFFFFFL)];
            if (filter == null || filter.shows(e)) out.add(e);
        }
        return out;
    }
}
