package dev.tboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import dev.tboard.engine.KeyGeometry;

/**
 * Draws the suggestion bar and keys, and turns touches into taps, swipes,
 * long-press popups and space-bar cursor slides.
 */
final class KeyboardView extends View {

    interface Listener {
        /** A key was tapped. x and y are the touch position in key widths (NaN for non-letter keys). */
        void onKey(int code, float x, float y);

        void onText(String text);

        /** A gesture ended. Returns false if no word matched; the view then types the start key. */
        boolean onSwipe(float[] xs, float[] ys, int count);

        void onSuggestionPicked(int index);

        void onCursorMove(int steps);

        void onSpaceLongPress();

        void onOpenSettings();

        void onGeometryChanged(KeyGeometry geometry);

        void onKeyDownFeedback();

        /** The paste button at the right end of the suggestion bar. */
        void onPaste();
    }

    private final float density;
    private Listener listener;
    private KeyboardLayout layout = KeyboardLayout.qwerty();
    private KeyboardTheme theme;

    // settings
    boolean swipeEnabled = true, showTrail = true, spaceCursor = true, keyPreview = true, numberHints = true;
    private float rowDp = 54f;
    private boolean stripVisible = true;

    // geometry in px
    private float unit;      // width of one key unit
    private float rowH;
    private float stripH;
    private float keysTop;
    private final float gap, radius;

    // state shown
    private int shiftState; // 0 off, 1 once, 2 locked
    private final String[] suggestions = new String[3];
    private int boldIndex = -1;
    private String enterLabel = "↵";

    // touch state
    private int activeId = -1;
    private KeyboardLayout.Key downKey;
    private float downX, downY;
    private boolean swiping, sliding, longPressFired, inStrip;
    private int stripDownIndex = -1;
    private float slideAnchorX;
    private float[] pathX = new float[256], pathY = new float[256];
    private int pathN;
    private String[] popupItems;
    private int popupSel = -1, popupCols = 1, popupRows = 1;
    private final RectF popupRect = new RectF();
    private KeyboardLayout.Key pressedKey;
    private long lastTapTime;
    private final boolean debuggable;
    private final int[] screenLoc = new int[2];
    private String loggedGeometry;

    /** Movement (in key widths) that turns a touch into a swipe; more right after a tap, so fast typing isn't misread. */
    private static final float SWIPE_START = 0.6f, SWIPE_START_AFTER_TAP = 1.2f;
    private static final long FAST_TYPING_MS = 300;

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path trail = new Path();

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            onLongPress();
        }
    };
    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (downKey != null && downKey.code == KeyboardLayout.DELETE && activeId != -1) {
                listener.onKey(KeyboardLayout.DELETE, Float.NaN, Float.NaN);
                postDelayed(this, 45);
            }
        }
    };

    KeyboardView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        gap = 3f * density;
        radius = 7f * density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        stripPaint.setTextAlign(Paint.Align.CENTER);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setStrokeWidth(5f * density);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(Math.max(1f, density));
        theme = KeyboardTheme.resolve(context, "system");
        debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTheme(KeyboardTheme t) {
        theme = t;
        invalidate();
    }

    void setRowHeightDp(float dp) {
        if (dp != rowDp) {
            rowDp = dp;
            requestLayout();
        }
    }

    /** Hides the suggestion bar (while the emoji search bar is shown above the keys). */
    void setStripVisible(boolean v) {
        if (v != stripVisible) {
            stripVisible = v;
            requestLayout();
        }
    }

    void setLayout(KeyboardLayout l) {
        layout = l;
        cancelTouch();
        invalidate();
    }

    KeyboardLayout getLayout() {
        return layout;
    }

    void setShiftState(int s) {
        if (s != shiftState) {
            shiftState = s;
            invalidate();
        }
    }

    void setEnterLabel(String label) {
        enterLabel = label;
        invalidate();
    }

    /** Three suggestion labels (any may be null); boldIndex marks the autocorrect choice. */
    void setSuggestions(String a, String b, String c, int bold) {
        suggestions[0] = a;
        suggestions[1] = b;
        suggestions[2] = c;
        boldIndex = bold;
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        float strip = stripVisible ? 44f * density : 0f;
        // landscape screens are short, so the keys get 75% of their portrait height there
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float row = landscape ? rowDp * 0.75f : rowDp;
        int h = (int) (strip + 4 * row * density + 6f * density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        unit = w / 10f;
        stripH = stripVisible ? 44f * density : 0f;
        keysTop = stripH;
        rowH = (h - stripH - 6f * density) / 4f;
        if (listener != null) listener.onGeometryChanged(buildGeometry());
    }

    /** Debug builds only: logs where the keys are on screen whenever that changes, for the screenshot driver. */
    private void logGeometry() {
        getLocationOnScreen(screenLoc);
        String g = screenLoc[0] + " " + screenLoc[1] + " " + getWidth() + " " + getHeight()
                + " " + unit + " " + rowH + " " + stripH;
        if (!g.equals(loggedGeometry)) {
            loggedGeometry = g;
            Log.i("LBGEOM", g);
        }
    }

    /** Letter key centers in key-width units, matching the coordinates passed to onKey/onSwipe. */
    KeyGeometry buildGeometry() {
        KeyboardLayout q = KeyboardLayout.qwerty();
        KeyGeometry g = new KeyGeometry();
        float rowUnits = unit > 0 ? rowH / unit : 1.5f;
        for (KeyboardLayout.Key k : q.keys) {
            if (k.isLetter()) g.set((char) k.code, k.x + k.w / 2f, (k.row + 0.5f) * rowUnits);
        }
        return g;
    }

    private float unitX(float px) {
        return px / unit;
    }

    private float unitY(float py) {
        return (py - keysTop) / unit;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(theme.background);
        if (stripVisible) drawStrip(canvas);
        for (KeyboardLayout.Key k : layout.keys) drawKey(canvas, k);
        if (swiping && showTrail && pathN > 1) drawTrail(canvas);
        if (debuggable) logGeometry();
        if (popupItems != null) drawPopup(canvas);
        else if (keyPreview && pressedKey != null && !swiping && !sliding && pressedKey.kind == KeyboardLayout.NORMAL
                && pressedKey.code != ' ') drawPreview(canvas, pressedKey);
    }

    /** Width of the paste button at the right end of the suggestion bar; the 3 suggestions share the rest. */
    private float pasteWidth() {
        return 48f * density;
    }

    private void drawStrip(Canvas canvas) {
        float pw = pasteWidth();
        float w = (getWidth() - pw) / 3f;
        stripPaint.setTextSize(17f * density);
        for (int i = 0; i < 3; i++) {
            String s = suggestions[i];
            if (s == null) continue;
            if (inStrip && stripDownIndex == i) {
                keyPaint.setColor(theme.keyPressed);
                rect.set(i * w + gap, gap, (i + 1) * w - gap, stripH - gap);
                canvas.drawRoundRect(rect, radius, radius, keyPaint);
            }
            stripPaint.setColor(theme.stripText);
            stripPaint.setTypeface(i == boldIndex ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            String label = ellipsize(s, w - 12f * density, stripPaint);
            Paint.FontMetrics fm = stripPaint.getFontMetrics();
            canvas.drawText(label, i * w + w / 2f, stripH / 2f - (fm.ascent + fm.descent) / 2f, stripPaint);
        }
        keyPaint.setColor(theme.divider);
        for (int i = 1; i < 3; i++) {
            if (suggestions[i - 1] == null && suggestions[i] == null) continue;
            canvas.drawRect(i * w - density / 2f, stripH * 0.3f, i * w + density / 2f, stripH * 0.7f, keyPaint);
        }
        // paste button: a clipboard outline in the dim text color
        float px = getWidth() - pw;
        if (inStrip && stripDownIndex == PASTE) {
            keyPaint.setColor(theme.keyPressed);
            rect.set(px + gap, gap, getWidth() - gap, stripH - gap);
            canvas.drawRoundRect(rect, radius, radius, keyPaint);
        }
        keyPaint.setColor(theme.divider);
        canvas.drawRect(px - density / 2f, stripH * 0.3f, px + density / 2f, stripH * 0.7f, keyPaint);
        float cx = px + pw / 2f, cy = stripH / 2f, bw = 7f * density, bh = 9f * density;
        outlinePaint.setColor(theme.textDim);
        float stroke = outlinePaint.getStrokeWidth();
        outlinePaint.setStrokeWidth(Math.max(1.5f, 1.8f * density));
        rect.set(cx - bw, cy - bh + density, cx + bw, cy + bh);
        canvas.drawRoundRect(rect, 2f * density, 2f * density, outlinePaint);
        rect.set(cx - bw * 0.5f, cy - bh - 1.5f * density, cx + bw * 0.5f, cy - bh + 3f * density);
        canvas.drawRoundRect(rect, density, density, outlinePaint);
        outlinePaint.setStrokeWidth(stroke);
    }

    static final int PASTE = 3;

    private static String ellipsize(String s, float max, Paint p) {
        if (p.measureText(s) <= max) return s;
        String e = s;
        while (e.length() > 1 && p.measureText(e + "…") > max) e = e.substring(0, e.length() - 1);
        return e + "…";
    }

    private void keyRect(KeyboardLayout.Key k, RectF out) {
        float top = keysTop + k.row * rowH;
        out.set(k.x * unit + gap, top + gap * 1.3f, (k.x + k.w) * unit - gap, top + rowH - gap * 1.3f);
    }

    private void drawKey(Canvas canvas, KeyboardLayout.Key k) {
        keyRect(k, rect);
        boolean pressed = k == pressedKey && !swiping;
        int fill;
        int fg = theme.text;
        if (k.kind == KeyboardLayout.ACCENT) {
            fill = theme.accent;
            fg = theme.accentText;
        } else if (k.kind == KeyboardLayout.SPECIAL) {
            fill = pressed ? theme.specialPressed : theme.special;
        } else {
            fill = pressed ? theme.keyPressed : theme.key;
        }
        if (k.code == KeyboardLayout.SHIFT && shiftState == 2) {
            fill = theme.accent;
            fg = theme.accentText;
        }
        keyPaint.setColor(theme.shadow);
        rect.offset(0, density);
        canvas.drawRoundRect(rect, radius, radius, keyPaint);
        rect.offset(0, -density);
        keyPaint.setColor(fill);
        canvas.drawRoundRect(rect, radius, radius, keyPaint);

        if (k.code == KeyboardLayout.EMOJI) {
            drawSmiley(canvas, rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.24f, fg);
            return;
        }
        String label = k.label;
        float size;
        if (k.code == KeyboardLayout.ENTER) {
            label = enterLabel;
            size = label.length() > 2 ? 14f : 22f;
        } else if (k.isLetter()) {
            if (shiftState > 0) label = label.toUpperCase(java.util.Locale.ROOT);
            size = Math.min(23f, rect.height() * 0.45f / density); // leaves room for the number hint on short keys
        } else if (k.code == KeyboardLayout.SHIFT) {
            label = shiftState > 0 ? "⬆" : "⇧";
            size = 22f;
        } else if (k.kind == KeyboardLayout.SPECIAL && label.length() > 1) {
            size = 15f;
        } else {
            size = 22f;
        }
        textPaint.setColor(fg);
        textPaint.setTypeface(k.kind == KeyboardLayout.SPECIAL && label.length() > 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        textPaint.setTextSize(Math.min(size * density, rowH * 0.5f));
        String shown = ellipsize(label, rect.width() - 6f * density, textPaint);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(shown, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint);

        if (numberHints && layout.letters) {
            String hint = k.hint();
            if (hint != null) {
                hintPaint.setColor(theme.textDim);
                hintPaint.setTextSize(10f * density);
                canvas.drawText(hint, rect.right - 6f * density, rect.top + 11f * density, hintPaint);
            }
        }
    }

    /** A line-drawn smiley in the key text color, so the emoji key matches the other keys. */
    private void drawSmiley(Canvas canvas, float cx, float cy, float r, int color) {
        outlinePaint.setColor(color);
        float stroke = outlinePaint.getStrokeWidth();
        outlinePaint.setStrokeWidth(Math.max(1.5f, 1.8f * density));
        canvas.drawCircle(cx, cy, r, outlinePaint);
        rect.set(cx - r * 0.55f, cy - r * 0.5f, cx + r * 0.55f, cy + r * 0.55f);
        canvas.drawArc(rect, 25f, 130f, false, outlinePaint);
        outlinePaint.setStrokeWidth(stroke);
        keyPaint.setColor(color);
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.25f, r * 0.12f, keyPaint);
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.25f, r * 0.12f, keyPaint);
    }

    private void drawTrail(Canvas canvas) {
        trail.reset();
        trail.moveTo(pathX[0], pathY[0]);
        for (int i = 1; i < pathN; i++) trail.lineTo(pathX[i], pathY[i]);
        trailPaint.setColor((theme.accent & 0x00FFFFFF) | 0xB0000000);
        canvas.drawPath(trail, trailPaint);
    }

    private void drawPreview(Canvas canvas, KeyboardLayout.Key k) {
        keyRect(k, rect);
        float w = Math.max(rect.width(), unit * 1.1f);
        float cx = rect.centerX();
        float bottom = rect.top - gap;
        float top = Math.max(0f, bottom - rowH * 1.05f);
        bottom = Math.max(bottom, top + rowH * 0.8f);
        rect.set(cx - w / 2f, top, cx + w / 2f, bottom);
        keyPaint.setColor(theme.shadow);
        rect.offset(0, density * 2);
        canvas.drawRoundRect(rect, radius, radius, keyPaint);
        rect.offset(0, -density * 2);
        keyPaint.setColor(theme.popup);
        canvas.drawRoundRect(rect, radius, radius, keyPaint);
        outlinePaint.setColor(theme.dark ? 0x40FFFFFF : 0x40000000); // so a white bubble stands out over white keys
        canvas.drawRoundRect(rect, radius, radius, outlinePaint);
        String label = k.label;
        if (k.isLetter() && shiftState > 0) label = label.toUpperCase(java.util.Locale.ROOT);
        textPaint.setColor(theme.text);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(30f * density);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint);
    }

    private void drawPopup(Canvas canvas) {
        keyPaint.setColor(theme.shadow);
        rect.set(popupRect);
        rect.offset(0, density * 2);
        canvas.drawRoundRect(rect, radius, radius, keyPaint);
        keyPaint.setColor(theme.popup);
        canvas.drawRoundRect(popupRect, radius, radius, keyPaint);
        outlinePaint.setColor(theme.dark ? 0x40FFFFFF : 0x40000000);
        canvas.drawRoundRect(popupRect, radius, radius, outlinePaint);
        float cw = popupRect.width() / popupCols, ch = popupRect.height() / popupRows;
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(Math.min(22f * density, ch * 0.5f));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        for (int i = 0; i < popupItems.length; i++) {
            float l = popupRect.left + (i % popupCols) * cw, t = popupRect.top + (i / popupCols) * ch;
            if (i == popupSel) {
                keyPaint.setColor(theme.popupSelected);
                rect.set(l + gap, t + gap, l + cw - gap, t + ch - gap);
                canvas.drawRoundRect(rect, radius, radius, keyPaint);
                textPaint.setColor(theme.accentText);
            } else {
                textPaint.setColor(theme.text);
            }
            String s = shownCase(popupItems[i]);
            canvas.drawText(s, l + cw / 2f, t + ch / 2f - (fm.ascent + fm.descent) / 2f, textPaint);
        }
    }

    /** Uppercases a popup item while shifted, unless that changes its length (ß → SS). */
    String shownCase(String s) {
        if (shiftState == 0) return s;
        String u = s.toUpperCase(java.util.Locale.ROOT);
        return u.length() == s.length() ? u : s;
    }

    // ------------------------------------------------------------------ touch

    private KeyboardLayout.Key keyAt(float x, float y) {
        if (y < keysTop) return null;
        int row = (int) ((y - keysTop) / rowH);
        if (row < 0) row = 0;
        if (row > 3) row = 3;
        float ux = x / unit;
        KeyboardLayout.Key best = null;
        float bestD = Float.MAX_VALUE;
        for (KeyboardLayout.Key k : layout.keys) {
            if (k.row != row) continue;
            if (ux >= k.x && ux < k.x + k.w) return k;
            float d = Math.min(Math.abs(ux - k.x), Math.abs(ux - (k.x + k.w)));
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    /** 0-2 for a suggestion, PASTE for the paste button, -1 for nothing. */
    private int stripIndexAt(float x, float y) {
        if (y < 0 || y >= stripH) return -1;
        float pw = pasteWidth();
        if (x >= getWidth() - pw) return PASTE;
        int i = (int) (x / ((getWidth() - pw) / 3f));
        if (i < 0 || i > 2 || suggestions[i] == null) return -1;
        return i;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (listener == null || unit <= 0) return true;
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (activeId != -1) {
                    if (swiping || sliding || popupItems != null || inStrip) return true; // ignore extra fingers
                    finishTap(); // fast two-thumb typing: the first key is done
                }
                startPointer(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int p = ev.findPointerIndex(activeId);
                if (p < 0) return true;
                int hs = ev.getHistorySize();
                if (swiping) {
                    for (int h = 0; h < hs; h++) addPoint(ev.getHistoricalX(p, h), ev.getHistoricalY(p, h));
                }
                movePointer(ev.getX(p), ev.getY(p));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (ev.getPointerId(idx) != activeId) return true;
                upPointer(ev.getX(idx), ev.getY(idx));
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                return true;
            default:
                return true;
        }
    }

    private void startPointer(int id, float x, float y) {
        activeId = id;
        downX = x;
        downY = y;
        swiping = false;
        sliding = false;
        longPressFired = false;
        popupItems = null;
        pathN = 0;
        inStrip = y < keysTop;
        if (inStrip) {
            downKey = null;
            pressedKey = null;
            stripDownIndex = stripIndexAt(x, y);
            invalidate();
            return;
        }
        downKey = keyAt(x, y);
        pressedKey = downKey;
        if (downKey == null) return;
        feedback();
        if (downKey.code == KeyboardLayout.DELETE) {
            listener.onKey(KeyboardLayout.DELETE, Float.NaN, Float.NaN);
            postDelayed(repeatRunnable, 400);
        } else if (downKey.more != null || downKey.code == ' ') {
            postDelayed(longPressRunnable, downKey.code == ' ' ? 600 : 350);
        }
        invalidate();
    }

    private void movePointer(float x, float y) {
        if (inStrip) {
            int i = stripIndexAt(x, y);
            if (i != stripDownIndex) {
                stripDownIndex = -1;
                invalidate();
            }
            return;
        }
        if (popupItems != null) {
            updatePopupSelection(x, y);
            return;
        }
        if (downKey == null) return;
        if (sliding) {
            float step = unit * 0.45f;
            int steps = (int) ((x - slideAnchorX) / step);
            if (steps != 0) {
                listener.onCursorMove(steps);
                slideAnchorX += steps * step;
            }
            return;
        }
        if (swiping) {
            addPoint(x, y);
            invalidate();
            return;
        }
        float dx = x - downX, dy = y - downY;
        if (downKey.code == ' ' && spaceCursor && Math.abs(dx) > unit * 0.6f) {
            sliding = true;
            removeCallbacks(longPressRunnable);
            slideAnchorX = x;
            return;
        }
        float start = SystemClock.uptimeMillis() - lastTapTime < FAST_TYPING_MS ? SWIPE_START_AFTER_TAP : SWIPE_START;
        if (swipeEnabled && layout.letters && downKey.isLetter() && Math.hypot(dx, dy) > unit * start) {
            swiping = true;
            removeCallbacks(longPressRunnable);
            pressedKey = null;
            pathN = 0;
            addPoint(downX, downY);
            addPoint(x, y);
            invalidate();
        }
    }

    private void upPointer(float x, float y) {
        removeCallbacks(longPressRunnable);
        removeCallbacks(repeatRunnable);
        if (inStrip) {
            int i = stripIndexAt(x, y);
            if (i == PASTE && stripDownIndex == PASTE) listener.onPaste();
            else if (i >= 0 && i == stripDownIndex) listener.onSuggestionPicked(i);
        } else if (popupItems != null) {
            if (popupSel >= 0 && popupSel < popupItems.length) {
                String s = popupItems[popupSel];
                if (KeyboardLayout.SETTINGS_ITEM.equals(s)) listener.onOpenSettings();
                else listener.onText(shownCase(s));
            }
        } else if (swiping) {
            addPoint(x, y);
            float[] xs = new float[pathN], ys = new float[pathN];
            for (int i = 0; i < pathN; i++) {
                xs[i] = unitX(pathX[i]);
                ys[i] = unitY(pathY[i]);
            }
            if (!listener.onSwipe(xs, ys, pathN)) fireTap();
        } else if (!sliding && !longPressFired && downKey != null && downKey.code != KeyboardLayout.DELETE) {
            fireTap();
        }
        resetTouch();
    }

    /** Ends the active pointer as a tap because another finger came down. */
    private void finishTap() {
        removeCallbacks(longPressRunnable);
        removeCallbacks(repeatRunnable);
        if (!longPressFired && downKey != null && downKey.code != KeyboardLayout.DELETE) fireTap();
        resetTouch();
    }

    private void fireTap() {
        KeyboardLayout.Key k = downKey;
        lastTapTime = SystemClock.uptimeMillis();
        if (k.isLetter()) listener.onKey(k.code, unitX(downX), unitY(downY));
        else listener.onKey(k.code, Float.NaN, Float.NaN);
    }

    private void resetTouch() {
        activeId = -1;
        downKey = null;
        pressedKey = null;
        swiping = false;
        sliding = false;
        inStrip = false;
        stripDownIndex = -1;
        popupItems = null;
        pathN = 0;
        invalidate();
    }

    void cancelTouch() {
        removeCallbacks(longPressRunnable);
        removeCallbacks(repeatRunnable);
        resetTouch();
    }

    private void addPoint(float x, float y) {
        if (pathN == pathX.length) {
            float[] nx = new float[pathN * 2], ny = new float[pathN * 2];
            System.arraycopy(pathX, 0, nx, 0, pathN);
            System.arraycopy(pathY, 0, ny, 0, pathN);
            pathX = nx;
            pathY = ny;
        }
        pathX[pathN] = x;
        pathY[pathN] = y;
        pathN++;
    }

    private void onLongPress() {
        if (downKey == null || swiping || sliding || activeId == -1) return;
        longPressFired = true;
        if (downKey.code == ' ') {
            pressedKey = null;
            invalidate();
            listener.onSpaceLongPress();
            return;
        }
        String[] items = downKey.more;
        if (!numberHints && layout.letters && downKey.hint() != null) {
            if (items.length <= 1) {
                longPressFired = false; // nothing to show; let the tap go through on release
                return;
            }
            String[] rest = new String[items.length - 1];
            System.arraycopy(items, 1, rest, 0, rest.length);
            items = rest;
        }
        popupItems = items;
        pressedKey = null;
        keyRect(downKey, rect);
        boolean grid = downKey.moreCols > 0 && items.length > downKey.moreCols;
        popupCols = grid ? downKey.moreCols : items.length;
        popupRows = (items.length + popupCols - 1) / popupCols;
        float cw = unit;
        float w = cw * popupCols;
        // one row starts above the key; a grid ends above it (its bottom-right cell is over the key)
        float left = grid ? rect.centerX() + cw / 2f - w : rect.centerX() - cw / 2f;
        if (left + w > getWidth()) left = getWidth() - w;
        if (left < 0) left = 0;
        float bottom = rect.top - gap;
        float ch = Math.min(rowH, bottom / popupRows);
        float top = Math.max(0f, bottom - ch * popupRows);
        bottom = Math.max(bottom, top + ch * 0.8f * popupRows);
        popupRect.set(left, top, left + w, bottom);
        popupSel = -1;
        updatePopupSelection(downX, downY); // the finger is below the popup: the cell right above it
        feedback();
        invalidate();
    }

    private void updatePopupSelection(float x, float y) {
        float cw = popupRect.width() / popupCols, ch = popupRect.height() / popupRows;
        int col = (int) Math.floor((x - popupRect.left) / cw), row = (int) Math.floor((y - popupRect.top) / ch);
        col = Math.max(0, Math.min(popupCols - 1, col));
        row = Math.max(0, Math.min(popupRows - 1, row));
        int i = Math.min(popupItems.length - 1, row * popupCols + col);
        if (i != popupSel) {
            popupSel = i;
            invalidate();
        }
    }

    private void feedback() {
        listener.onKeyDownFeedback();
    }

    void haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
}
