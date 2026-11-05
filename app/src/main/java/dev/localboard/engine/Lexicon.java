package dev.localboard.engine;

import java.util.ArrayList;

/**
 * The full vocabulary the decoders search: the bundled dictionary plus the
 * words this person has taught the keyboard.
 */
public final class Lexicon {
    public final Dictionary dict;
    public final UserModel user;
    private int extrasVersion = -1;
    private final ArrayList<ArrayList<String>> extrasByFirst = new ArrayList<>();

    public Lexicon(Dictionary dict, UserModel user) {
        this.dict = dict;
        this.user = user;
        for (int i = 0; i < 26; i++) extrasByFirst.add(new ArrayList<String>());
    }

    /** User words outside the dictionary that start with letter index f. */
    public ArrayList<String> extras(int f) {
        if (extrasVersion != user.knownVersion()) {
            extrasVersion = user.knownVersion();
            for (ArrayList<String> l : extrasByFirst) l.clear();
            for (String k : user.knownExtraWords(dict)) {
                int i = Dictionary.letterIndex(k.charAt(0));
                if (i >= 0) extrasByFirst.get(i).add(k);
            }
        }
        return extrasByFirst.get(f);
    }

    /** Log prior combining dictionary frequency and personal usage; -inf if unknown. */
    public float prior(String key, int dictId) {
        float base = dictId >= 0 ? dict.logPrior[dictId] : Float.NEGATIVE_INFINITY;
        return user.prior(key, base);
    }

    public String display(String key, int dictId) {
        if (dictId >= 0) return dict.display[dictId];
        String d = user.displayOf(key);
        return d == null ? key : d;
    }

    public boolean isKnown(String key) {
        int id = dict.find(key);
        return id >= 0 || user.isKnown(key);
    }

    public boolean isOffensive(String key) {
        int id = dict.find(key);
        return id >= 0 && dict.offensive[id] && !user.isKnown(key);
    }
}
