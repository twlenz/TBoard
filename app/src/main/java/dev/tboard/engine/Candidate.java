package dev.tboard.engine;

/** One possible word with its score (higher is better) and its match cost (lower is better). */
public final class Candidate {
    public final String key;
    public final String display;
    public final float score;
    public final float cost;
    public final boolean offensive;

    public Candidate(String key, String display, float score, float cost, boolean offensive) {
        this.key = key;
        this.display = display;
        this.score = score;
        this.cost = cost;
        this.offensive = offensive;
    }

    @Override
    public String toString() {
        return display + "(" + score + "/" + cost + ")";
    }
}
