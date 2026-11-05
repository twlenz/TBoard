package dev.tboard.engine;

/**
 * Centers of the letter keys, in units of one key width. The keyboard view
 * builds this from its real layout, so the decoders see the same geometry
 * the finger does.
 */
public final class KeyGeometry {
    private final float[] cx = new float[26];
    private final float[] cy = new float[26];
    private final boolean[] has = new boolean[26];

    public void set(char c, float x, float y) {
        int i = Dictionary.letterIndex(c);
        if (i < 0) return;
        cx[i] = x;
        cy[i] = y;
        has[i] = true;
    }

    public boolean has(char c) {
        int i = Dictionary.letterIndex(c);
        return i >= 0 && has[i];
    }

    public float x(char c) {
        return cx[c - 'a'];
    }

    public float y(char c) {
        return cy[c - 'a'];
    }

    /** Standard QWERTY rows; rowHeight is the vertical distance between rows in key widths. */
    public static KeyGeometry qwerty(float rowHeight) {
        KeyGeometry g = new KeyGeometry();
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] offset = {0.5f, 1.0f, 2.0f};
        for (int r = 0; r < rows.length; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                g.set(rows[r].charAt(i), offset[r] + i, (r + 0.5f) * rowHeight);
            }
        }
        return g;
    }
}
