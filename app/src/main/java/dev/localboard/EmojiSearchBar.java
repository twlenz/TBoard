package dev.localboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Shown above the letter keys while searching emoji: the search text, a close button, and a row of
 * matching emoji. The keyboard types into it instead of the app.
 */
final class EmojiSearchBar extends LinearLayout {
    interface Listener {
        void onSearchPick(String emoji);

        void onSearchClose();
    }

    private final float density;
    private final TextView query, close, empty;
    private final LinearLayout results;
    private final HorizontalScrollView scroll;
    private final IconView icon;
    private Listener listener;
    private KeyboardTheme theme;

    EmojiSearchBar(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        icon = new IconView(ctx, IconView.SEARCH);
        top.addView(icon, new LayoutParams(dp(44), LayoutParams.MATCH_PARENT));
        query = new TextView(ctx);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        query.setSingleLine(true);
        query.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(query, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> {
            if (listener != null) listener.onSearchClose();
        });
        top.addView(close, new LayoutParams(dp(48), LayoutParams.MATCH_PARENT));
        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));

        scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        results = new LinearLayout(ctx);
        results.setOrientation(HORIZONTAL);
        scroll.addView(results, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(50)));
        empty = new TextView(ctx);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER_VERTICAL);
        empty.setPadding(dp(14), 0, 0, 0);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTheme(KeyboardTheme t) {
        theme = t;
        setBackgroundColor(t.background);
        query.setTextColor(t.text);
        query.setHintTextColor(t.textDim);
        close.setTextColor(t.text);
        empty.setTextColor(t.textDim);
        icon.setColor(t.textDim);
        for (int i = 0; i < results.getChildCount(); i++) ((TextView) results.getChildAt(i)).setTextColor(t.text);
    }

    /** Shows the query and its matches (or recent emoji when the query is empty). */
    void show(String text, List<String> found) {
        query.setText(text);
        query.setHint(text.isEmpty() ? "Search emoji" : null);
        results.removeAllViews();
        if (found.isEmpty()) {
            empty.setText(text.isEmpty() ? "Type to search" : "No matching emoji");
            results.addView(empty, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }
        for (String e : found) {
            TextView t = new TextView(getContext());
            t.setText(e);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            t.setGravity(Gravity.CENTER);
            if (theme != null) t.setTextColor(theme.text);
            t.setOnClickListener(v -> {
                if (listener != null) listener.onSearchPick(e);
            });
            results.addView(t, new LayoutParams(dp(50), LayoutParams.MATCH_PARENT));
        }
        scroll.scrollTo(0, 0);
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    /** Small line icons drawn in one color, so they match the keys (no color emoji). */
    static final class IconView extends View {
        static final int SEARCH = 0;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;

        IconView(Context ctx, int kind) {
            super(ctx);
            this.kind = kind;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(2f * ctx.getResources().getDisplayMetrics().density);
        }

        void setColor(int c) {
            paint.setColor(c);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (kind == SEARCH) drawSearch(canvas, paint, cx, cy, 8f * d);
        }

        static void drawSearch(Canvas c, Paint p, float cx, float cy, float r) {
            float ox = cx - r * 0.25f, oy = cy - r * 0.25f;
            c.drawCircle(ox, oy, r * 0.7f, p);
            c.drawLine(ox + r * 0.5f, oy + r * 0.5f, cx + r * 0.95f, cy + r * 0.95f, p);
        }
    }
}
