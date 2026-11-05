package dev.localboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;

/** Emoji grid with category tabs and a recently-used list (stored on the device). */
final class EmojiPanel extends LinearLayout {

    interface Listener {
        void onEmoji(String emoji);

        void onEmojiBack();

        void onEmojiDelete();

        void onEmojiSearch();
    }

    static final String RECENTS = "recent_emoji";
    private static final int MAX_RECENTS = 32;

    private final float density;
    private final GridView grid;
    private final LinearLayout tabs;
    private final TextView abc, del;
    private final ArrayList<TextView> tabViews = new ArrayList<>();
    private final SharedPreferences prefs;
    private Listener listener;
    private KeyboardTheme theme;
    private String[] items = new String[0];
    private int category;
    private final EmojiSearchBar.IconView searchIcon;
    // which emoji this phone's fonts can draw (older Android versions lack newer emoji)
    private final Paint glyphPaint = new Paint();
    private final HashMap<String, Boolean> drawable = new HashMap<>();
    private final String[][] shownByCategory = new String[EmojiData.CATEGORIES.length][];

    EmojiPanel(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        prefs = Prefs.get(ctx);
        setOrientation(VERTICAL);

        grid = new GridView(ctx);
        grid.setNumColumns(8);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(0);
        grid.setHorizontalSpacing(0);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (listener != null && position < items.length) {
                String e = items[position];
                addRecent(e);
                listener.onEmoji(e);
            }
        });
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        abc = barButton(ctx, "ABC");
        abc.setTypeface(Typeface.DEFAULT_BOLD);
        abc.setOnClickListener(v -> {
            if (listener != null) listener.onEmojiBack();
        });
        bar.addView(abc, new LayoutParams(dp(60), LayoutParams.MATCH_PARENT));
        searchIcon = new EmojiSearchBar.IconView(ctx, EmojiSearchBar.IconView.SEARCH);
        searchIcon.setOnClickListener(v -> {
            if (listener != null) listener.onEmojiSearch();
        });
        bar.addView(searchIcon, new LayoutParams(dp(46), LayoutParams.MATCH_PARENT));

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(ctx);
        tabs.setOrientation(HORIZONTAL);
        scroll.addView(tabs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(scroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        del = barButton(ctx, "⌫");
        del.setOnClickListener(v -> {
            if (listener != null) listener.onEmojiDelete();
        });
        bar.addView(del, new LayoutParams(dp(60), LayoutParams.MATCH_PARENT));
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(46)));

        addTab(ctx, "🕘", -1); // clock: recents
        for (int i = 0; i < EmojiData.CATEGORIES.length; i++) addTab(ctx, EmojiData.ICONS[i], i);
        showCategory(recents().isEmpty() ? 0 : -1);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTheme(KeyboardTheme t) {
        theme = t;
        setBackgroundColor(t.background);
        abc.setTextColor(t.text);
        del.setTextColor(t.text);
        searchIcon.setColor(t.text);
        updateTabs();
        adapter.notifyDataSetChanged();
    }

    /** Called each time the panel opens, so recents are current. */
    void refresh() {
        if (category == -1) showCategory(-1);
    }

    private TextView barButton(Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        return t;
    }

    private void addTab(Context ctx, String icon, final int cat) {
        TextView t = new TextView(ctx);
        t.setText(icon);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setOnClickListener(v -> showCategory(cat));
        t.setTag(cat);
        tabs.addView(t, new LayoutParams(dp(46), LayoutParams.MATCH_PARENT));
        tabViews.add(t);
    }

    private void showCategory(int cat) {
        category = cat;
        if (cat == -1) {
            ArrayList<String> r = recents();
            items = r.toArray(new String[0]);
        } else {
            if (shownByCategory[cat] == null) {
                ArrayList<String> ok = new ArrayList<>();
                for (String e : EmojiData.CATEGORIES[cat].split(" ")) if (canShow(e)) ok.add(e);
                shownByCategory[cat] = ok.toArray(new String[0]);
            }
            items = shownByCategory[cat];
        }
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        updateTabs();
    }

    private void updateTabs() {
        if (theme == null) return;
        for (TextView t : tabViews) {
            boolean sel = ((Integer) t.getTag()) == category;
            t.setBackgroundColor(sel ? theme.keyPressed : 0);
        }
    }

    ArrayList<String> recents() {
        ArrayList<String> out = new ArrayList<>();
        String s = prefs.getString(RECENTS, "");
        for (String e : s.split(" ")) if (!e.isEmpty()) out.add(e);
        return out;
    }

    /** True if the phone's fonts draw this emoji as one picture (not boxes or separate pieces). */
    boolean canShow(String e) {
        Boolean b = drawable.get(e);
        if (b == null) {
            b = glyphPaint.hasGlyph(e);
            // Android 6-7 report "no glyph" for emoji written with the emoji-style marker U+FE0F
            // (like the red heart) even though they draw the character itself fine
            if (!b && e.indexOf('\uFE0F') >= 0) b = glyphPaint.hasGlyph(e.replace("\uFE0F", ""));
            drawable.put(e, b);
        }
        return b;
    }

    void addRecent(String e) {
        ArrayList<String> r = recents();
        r.remove(e);
        r.add(0, e);
        while (r.size() > MAX_RECENTS) r.remove(r.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String x : r) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(x);
        }
        prefs.edit().putString(RECENTS, sb.toString()).apply();
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.length;
        }

        @Override
        public Object getItem(int position) {
            return items[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView : null;
            if (t == null) {
                t = new TextView(parent.getContext());
                t.setGravity(Gravity.CENTER);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                t.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, dp(50)));
            }
            t.setText(items[position]);
            if (theme != null) t.setTextColor(theme.text);
            return t;
        }
    };
}
