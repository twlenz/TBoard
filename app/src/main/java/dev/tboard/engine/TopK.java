package dev.tboard.engine;

import java.util.ArrayList;

/** Keeps the k best candidates by score, one per key. */
final class TopK {
    private final Candidate[] items;
    private int size;

    TopK(int k) {
        items = new Candidate[k];
    }

    float threshold() {
        return size < items.length ? Float.NEGATIVE_INFINITY : items[size - 1].score;
    }

    void offer(Candidate c) {
        for (int i = 0; i < size; i++) {
            if (items[i].key.equals(c.key)) {
                if (items[i].score >= c.score) return;
                System.arraycopy(items, i + 1, items, i, size - i - 1);
                size--;
                break;
            }
        }
        if (size == items.length && c.score <= items[size - 1].score) return;
        int pos = size < items.length ? size : size - 1;
        while (pos > 0 && items[pos - 1].score < c.score) {
            if (pos < items.length) items[pos] = items[pos - 1];
            pos--;
        }
        items[pos] = c;
        if (size < items.length) size++;
    }

    ArrayList<Candidate> toList() {
        ArrayList<Candidate> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(items[i]);
        return out;
    }
}
