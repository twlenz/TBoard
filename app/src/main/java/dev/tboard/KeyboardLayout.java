package dev.tboard;

import java.util.ArrayList;

/**
 * Key positions. Each row is 10 units wide; a normal key is 1 unit wide and
 * one row tall. The view scales units to pixels.
 */
final class KeyboardLayout {
    static final int SHIFT = -1, SYMBOLS = -2, SYMBOLS2 = -3, ALPHA = -4, DELETE = -5, ENTER = -6, EMOJI = -7;
    static final String SETTINGS_ITEM = "⚙";

    static final int NORMAL = 0, SPECIAL = 1, ACCENT = 2;

    static final class Key {
        final int code;
        String label;
        final String[] more;
        final float x, w;
        final int row;
        final int kind;

        Key(int code, String label, String[] more, float x, float w, int row, int kind) {
            this.code = code;
            this.label = label;
            this.more = more;
            this.x = x;
            this.w = w;
            this.row = row;
            this.kind = kind;
        }

        boolean isLetter() {
            return code >= 'a' && code <= 'z';
        }

        String hint() {
            return more != null && more.length > 0 && more[0].length() == 1
                    && Character.isDigit(more[0].charAt(0)) ? more[0] : null;
        }
    }

    final ArrayList<Key> keys = new ArrayList<>();
    final boolean letters;
    final int rows = 4;

    private KeyboardLayout(boolean letters) {
        this.letters = letters;
    }

    private void row(int r, float x, String chars, String[][] more) {
        for (int i = 0; i < chars.length(); i++) {
            String c = String.valueOf(chars.charAt(i));
            keys.add(new Key(chars.charAt(i), c, more == null ? null : more[i], x + i, 1f, r, NORMAL));
        }
    }

    private void add(int code, String label, float x, float w, int r, int kind, String[] more) {
        keys.add(new Key(code, label, more, x, w, r, kind));
    }

    private static final String[] PERIOD_MORE = {"?", "!", "'", "\"", ":", ";", "-", "/", "@", "&"};
    private static final String[] COMMA_MORE = {SETTINGS_ITEM, ";", ":"};

    private void bottomRow(boolean alphaSwitch) {
        add(alphaSwitch ? ALPHA : SYMBOLS, alphaSwitch ? "ABC" : "?123", 0f, 1.5f, 3, SPECIAL, null);
        add(',', ",", 1.5f, 1f, 3, SPECIAL, COMMA_MORE);
        add(EMOJI, "☺", 2.5f, 1f, 3, SPECIAL, null);
        add(' ', "", 3.5f, 4f, 3, NORMAL, null);
        add('.', ".", 7.5f, 1f, 3, SPECIAL, PERIOD_MORE);
        add(ENTER, "↵", 8.5f, 1.5f, 3, ACCENT, null);
    }

    static KeyboardLayout qwerty() {
        KeyboardLayout k = new KeyboardLayout(true);
        k.row(0, 0f, "qwertyuiop", new String[][]{
                {"1"}, {"2"}, {"3", "é", "è", "ê", "ë", "ē"}, {"4"}, {"5"},
                {"6", "ý", "ÿ"}, {"7", "ú", "ù", "û", "ü"}, {"8", "í", "ì", "î", "ï"},
                {"9", "ó", "ò", "ô", "ö", "õ", "ø"}, {"0"}});
        k.row(1, 0.5f, "asdfghjkl", new String[][]{
                {"á", "à", "â", "ä", "ã", "å", "æ"}, {"ß", "ś", "š"}, null, null, null, null, null, null, {"ł"}});
        k.add(SHIFT, "⇧", 0f, 1.5f, 2, SPECIAL, null);
        k.row(2, 1.5f, "zxcvbnm", new String[][]{
                {"ž", "ź", "ż"}, null, {"ç", "ć", "č"}, null, null, {"ñ", "ń"}, null});
        k.add(DELETE, "⌫", 8.5f, 1.5f, 2, SPECIAL, null);
        k.bottomRow(false);
        return k;
    }

    static KeyboardLayout symbols() {
        KeyboardLayout k = new KeyboardLayout(false);
        k.row(0, 0f, "1234567890", new String[][]{
                {"¹", "½", "⅓", "¼"}, {"²", "⅔"}, {"³", "¾"}, {"⁴"}, {"⁵"}, {"⁶"}, {"⁷"}, {"⁸"}, {"⁹"}, {"⁰", "ⁿ", "∅"}});
        k.row(1, 0f, "@#$_&-+()/", new String[][]{
                null, {"№"}, {"€", "£", "¥", "¢", "₹"}, null, null, {"–", "—", "·"}, {"±"}, {"<", "[", "{"}, {">", "]", "}"}, {"\\"}});
        k.add(SYMBOLS2, "=\\<", 0f, 1.5f, 2, SPECIAL, null);
        k.row(2, 1.5f, "*\"':;!?", new String[][]{
                {"★", "†"}, {"“", "”", "«", "»"}, {"‘", "’", "`"}, null, null, {"¡"}, {"¿"}});
        k.add(DELETE, "⌫", 8.5f, 1.5f, 2, SPECIAL, null);
        k.bottomRow(true);
        return k;
    }

    static KeyboardLayout symbols2() {
        KeyboardLayout k = new KeyboardLayout(false);
        k.row(0, 0f, "~`|•√π÷×¶∆", null);
        k.row(1, 0f, "£¢€¥^°={}\\", null);
        k.add(SYMBOLS, "?123", 0f, 1.5f, 2, SPECIAL, null);
        k.row(2, 1.5f, "%©®™✓[]", null);
        k.add(DELETE, "⌫", 8.5f, 1.5f, 2, SPECIAL, null);
        k.bottomRow(true);
        return k;
    }
}
