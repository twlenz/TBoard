"""Writes minimal stand-ins for the Android classes the app uses, so the app compiles and its
logic can be tested with plain javac/java when no Android SDK is available. Usage:
    python3 android_stubs.py OUTPUT_DIR
These are NOT the real Android APIs; they only exist for tools/run_tests.sh."""
import os, sys, textwrap
root = sys.argv[1]
S = {}
S['android.content.Context'] = '''public abstract class Context {
 public static final int MODE_PRIVATE = 0; public static final String AUDIO_SERVICE="audio"; public static final String INPUT_METHOD_SERVICE="input_method";
 public abstract android.content.SharedPreferences getSharedPreferences(String n, int m);
 public abstract android.content.res.Resources getResources();
 public final int getColor(int id){return 0;}
 public abstract android.content.res.AssetManager getAssets();
 public abstract java.io.File getFilesDir();
 public abstract Context getApplicationContext();
 public abstract Object getSystemService(String n);
 public abstract void startActivity(android.content.Intent i);
 public abstract String getPackageName();
 public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}
 public abstract android.content.ContentResolver getContentResolver();
}'''
S['android.content.ContextWrapper'] = '''public class ContextWrapper extends Context {
 public static final java.util.HashMap<String,Object> PREFS = new java.util.HashMap<>();
 public android.content.SharedPreferences getSharedPreferences(String n, int m){ return new android.content.SharedPreferences(){
   public boolean getBoolean(String k, boolean d){ Object v=PREFS.get(k); return v==null?d:(Boolean)v; }
   public String getString(String k, String d){ Object v=PREFS.get(k); return v==null?d:(String)v; }
   public int getInt(String k, int d){ Object v=PREFS.get(k); return v==null?d:(Integer)v; }
   public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){} public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
   public Editor edit(){ return new Editor(){ public Editor putBoolean(String k, boolean v){PREFS.put(k,v);return this;} public Editor putString(String k, String v){PREFS.put(k,v);return this;}
     public Editor putInt(String k, int v){PREFS.put(k,v);return this;} public void apply(){} public boolean commit(){return true;} }; } }; }
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public android.content.res.AssetManager getAssets(){return null;}
 public java.io.File getFilesDir(){return null;}
 public Context getApplicationContext(){return this;}
 public Object getSystemService(String n){return null;}
 public void startActivity(android.content.Intent i){}
 public String getPackageName(){return null;}
 public android.content.ContentResolver getContentResolver(){return null;}
}'''
S['android.content.ContentResolver'] = 'public abstract class ContentResolver {}'
S['android.content.DialogInterface'] = '''public interface DialogInterface { interface OnClickListener { void onClick(DialogInterface d, int which); } }'''
S['android.content.SharedPreferences'] = '''public interface SharedPreferences {
 boolean getBoolean(String k, boolean d); String getString(String k, String d); int getInt(String k, int d); Editor edit();
 void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l); void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
 interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences p, String key); }
 interface Editor { Editor putBoolean(String k, boolean v); Editor putString(String k, String v); Editor putInt(String k, int v); void apply(); boolean commit(); }
}'''
S['android.content.Intent'] = '''public class Intent { public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
 public Intent(String action){} public Intent(android.content.Context c, Class<?> cls){} public Intent addFlags(int f){return this;} }'''
S['android.content.res.Resources'] = '''public class Resources { public android.util.DisplayMetrics getDisplayMetrics(){android.util.DisplayMetrics d=new android.util.DisplayMetrics(); d.density=2.75f; return d;} public Configuration getConfiguration(){return new Configuration();} }'''
S['android.content.res.Configuration'] = '''public final class Configuration { public static final int UI_MODE_NIGHT_MASK=0x30, UI_MODE_NIGHT_YES=0x20, ORIENTATION_LANDSCAPE=2; public int uiMode, orientation; }'''
S['android.content.res.AssetManager'] = '''public final class AssetManager { public java.io.InputStream open(String f) throws java.io.IOException {return null;} }'''
S['android.util.DisplayMetrics'] = 'public class DisplayMetrics { public float density; }'
S['android.util.TypedValue'] = 'public class TypedValue { public static final int COMPLEX_UNIT_SP = 2; }'
S['android.util.Log'] = 'public final class Log { public static int e(String t, String m, Throwable e){return 0;} public static int e(String t, String m){return 0;} public static int i(String t, String m){return 0;} }'
S['android.content.pm.ApplicationInfo'] = 'public class ApplicationInfo { public static final int FLAG_DEBUGGABLE = 2; public int flags; }'
S['android.R'] = 'public final class R { public static final class color { public static final int system_accent1_300=1, system_accent1_600=2; } }'
S['android.os.Build'] = 'public class Build { public static class VERSION { public static final int SDK_INT = 0; } }'
S['android.os.Bundle'] = 'public final class Bundle {}'
S['android.os.Looper'] = 'public final class Looper { public static Looper getMainLooper(){return null;} }'
S['android.os.Handler'] = 'public class Handler { public Handler(Looper l){} public final boolean post(Runnable r){return true;} }'
S['android.os.SystemClock'] = 'public final class SystemClock { public static long uptimeMillis(){return 0;} }'
S['android.provider.Settings'] = '''public final class Settings { public static final String ACTION_INPUT_METHOD_SETTINGS = "x";
 public static final class Secure { public static final String DEFAULT_INPUT_METHOD = "default_input_method"; public static String getString(android.content.ContentResolver r, String n){return null;} } }'''
S['android.text.InputType'] = '''public interface InputType {
 int TYPE_MASK_CLASS=0xf, TYPE_MASK_VARIATION=0xff0, TYPE_MASK_FLAGS=0xfff000, TYPE_CLASS_TEXT=1, TYPE_CLASS_NUMBER=2, TYPE_CLASS_PHONE=3, TYPE_CLASS_DATETIME=4,
 TYPE_TEXT_VARIATION_PASSWORD=0x80, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD=0x90, TYPE_TEXT_VARIATION_WEB_PASSWORD=0xe0, TYPE_NUMBER_VARIATION_PASSWORD=0x10,
 TYPE_TEXT_VARIATION_EMAIL_ADDRESS=0x20, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS=0xd0, TYPE_TEXT_VARIATION_URI=0x10, TYPE_TEXT_VARIATION_FILTER=0xb0,
 TYPE_TEXT_FLAG_NO_SUGGESTIONS=0x80000, TYPE_TEXT_FLAG_CAP_SENTENCES=0x4000, TYPE_TEXT_FLAG_MULTI_LINE=0x20000; }'''
S['android.text.TextUtils'] = 'public class TextUtils { public static final int CAP_MODE_SENTENCES = 0x4000; }'
S['android.graphics.Canvas'] = '''public class Canvas { public void drawColor(int c){} public void drawRoundRect(RectF r, float rx, float ry, Paint p){}
 public void drawText(String t, float x, float y, Paint p){} public void drawRect(float l, float t, float r, float b, Paint p){} public void drawPath(Path p, Paint pa){}
 public void drawCircle(float x, float y, float r, Paint p){} public void drawArc(RectF o, float s, float w, boolean c, Paint p){} public void drawLine(float a, float b, float c, float d, Paint p){} }'''
S['android.graphics.Paint'] = '''public class Paint { public static final int ANTI_ALIAS_FLAG = 1; public Paint(int f){} public Paint(){}
 private float sw; public float getStrokeWidth(){return sw;} public boolean hasGlyph(String s){return true;}
 public enum Align { LEFT, CENTER, RIGHT } public enum Style { FILL, STROKE } public enum Cap { BUTT, ROUND } public enum Join { MITER, ROUND }
 public static class FontMetrics { public float ascent, descent, top, bottom, leading; }
 public void setTextAlign(Align a){} public void setStyle(Style s){} public void setStrokeCap(Cap c){} public void setStrokeJoin(Join j){}
 public void setStrokeWidth(float w){} public void setColor(int c){} public void setTextSize(float s){} public Typeface setTypeface(Typeface t){return t;}
 public float measureText(String s){return 0;} public FontMetrics getFontMetrics(){return null;} }'''
S['android.graphics.Path'] = 'public class Path { public void reset(){} public void moveTo(float x, float y){} public void lineTo(float x, float y){} }'
S['android.graphics.RectF'] = '''public class RectF { public float left, top, right, bottom; public final void set(float l, float t, float r, float b){} public void set(RectF o){} public final float height(){return bottom-top;}
 public void offset(float dx, float dy){} public final float width(){return 0;} public final float centerX(){return 0;} public final float centerY(){return 0;} }'''
S['android.graphics.Typeface'] = 'public class Typeface { public static final Typeface DEFAULT = null, DEFAULT_BOLD = null; }'
S['android.media.AudioManager'] = 'public class AudioManager { public static final int FX_KEYPRESS_STANDARD = 5; public void playSoundEffect(int e, float v){} }'
S['android.view.HapticFeedbackConstants'] = 'public class HapticFeedbackConstants { public static final int KEYBOARD_TAP = 3; }'
S['android.view.Gravity'] = 'public class Gravity { public static final int CENTER=17, CENTER_VERTICAL=16, START=0x800003; }'
S['android.view.KeyEvent'] = 'public class KeyEvent { public static final int KEYCODE_DEL=67, KEYCODE_DPAD_LEFT=21, KEYCODE_DPAD_RIGHT=22, KEYCODE_ENTER=66; }'
S['android.view.MotionEvent'] = '''public final class MotionEvent { public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3, ACTION_POINTER_DOWN=5, ACTION_POINTER_UP=6;
 public final int getActionMasked(){return 0;} public final int getActionIndex(){return 0;} public final int getPointerId(int i){return 0;}
 public final float getX(int i){return 0;} public final float getY(int i){return 0;} public final int findPointerIndex(int id){return 0;}
 public final int getHistorySize(){return 0;} public final float getHistoricalX(int p, int h){return 0;} public final float getHistoricalY(int p, int h){return 0;} }'''
S['android.view.View'] = '''public class View { public static final int VISIBLE=0, INVISIBLE=4, GONE=8;
 public View(android.content.Context c){} 
 public interface OnClickListener { void onClick(View v); }
 public static class MeasureSpec { public static int getSize(int s){return 0;} }
 public android.content.res.Resources getResources(){return new android.content.res.Resources();} public void invalidate(){} public void requestLayout(){}
 public boolean postDelayed(Runnable r, long d){return true;} public boolean post(Runnable r){return true;} public void getLocationOnScreen(int[] o){} public boolean removeCallbacks(Runnable r){return true;}
 public boolean performHapticFeedback(int c){return true;} public final int getWidth(){return 0;} public final int getHeight(){return 0;}
 protected final void setMeasuredDimension(int w, int h){} protected void onMeasure(int w, int h){} protected void onSizeChanged(int w, int h, int ow, int oh){}
 protected void onDraw(android.graphics.Canvas c){} public boolean onTouchEvent(MotionEvent e){return false;}
 public void setVisibility(int v){} public ViewGroup.LayoutParams getLayoutParams(){return null;} public void setLayoutParams(ViewGroup.LayoutParams p){}
 public void setBackgroundColor(int c){} public void setOnClickListener(OnClickListener l){} private Object tag; public void setTag(Object o){tag=o;} public Object getTag(){return tag;}
 public void setPadding(int l, int t, int r, int b){} public void setAlpha(float a){} public void setFitsSystemWindows(boolean b){} public void setId(int id){}
 public void setEnabled(boolean e){} public static int generateViewId(){return 1;} public final <T extends View> T findViewById(int id){return null;}
 public void setHorizontalScrollBarEnabled(boolean b){} public void scrollTo(int x, int y){} public android.content.Context getContext(){return null;} }'''
S['android.view.ViewGroup'] = '''public abstract class ViewGroup extends View { public ViewGroup(android.content.Context c){super(c);}
 public int getChildCount(){return 0;} public View getChildAt(int i){return null;} public void removeAllViews(){}
 public void addView(View v){} public void addView(View v, LayoutParams p){}
 public static class LayoutParams { public static final int MATCH_PARENT=-1, WRAP_CONTENT=-2; public int width, height; public LayoutParams(int w, int h){} }
 public static class MarginLayoutParams extends LayoutParams { public int topMargin, leftMargin, rightMargin, bottomMargin; public MarginLayoutParams(int w, int h){super(w,h);} } }'''
S['android.view.inputmethod.EditorInfo'] = '''public class EditorInfo { public int inputType, imeOptions;
 public static final int IME_MASK_ACTION=0xff, IME_ACTION_UNSPECIFIED=0, IME_ACTION_NONE=1, IME_ACTION_GO=2, IME_ACTION_SEARCH=3, IME_ACTION_SEND=4, IME_ACTION_NEXT=5, IME_ACTION_DONE=6, IME_ACTION_PREVIOUS=7,
 IME_FLAG_NO_ENTER_ACTION=0x40000000, IME_FLAG_NO_PERSONALIZED_LEARNING=0x1000000; }'''
S['android.view.inputmethod.InputConnection'] = '''public interface InputConnection { CharSequence getTextBeforeCursor(int n, int f); CharSequence getSelectedText(int f);
 boolean finishComposingText(); boolean setComposingText(CharSequence t, int p); boolean commitText(CharSequence t, int p);
 boolean deleteSurroundingText(int b, int a); boolean performEditorAction(int a); int getCursorCapsMode(int m);
 CharSequence getTextAfterCursor(int n, int f); boolean beginBatchEdit(); boolean endBatchEdit(); }'''
S['android.view.inputmethod.InputMethodManager'] = '''public final class InputMethodManager { public void showInputMethodPicker(){} public java.util.List<InputMethodInfo> getEnabledInputMethodList(){return null;} }'''
S['android.view.inputmethod.InputMethodInfo'] = 'public final class InputMethodInfo { public String getPackageName(){return null;} }'
S['android.inputmethodservice.InputMethodService'] = '''public class InputMethodService extends android.content.ContextWrapper {
 public void onCreate(){} public void onDestroy(){} public boolean onEvaluateFullscreenMode(){return false;} public android.view.View onCreateInputView(){return null;}
 public void onStartInputView(android.view.inputmethod.EditorInfo i, boolean r){} public void onFinishInputView(boolean f){} public void onFinishInput(){}
 public void onUpdateSelection(int a, int b, int c, int d, int e, int f){}
 public static android.view.inputmethod.InputConnection testIC; public static java.util.function.IntConsumer testKeys;
 public android.view.inputmethod.InputConnection getCurrentInputConnection(){return testIC;}
 public void sendKeyChar(char c){ testIC.commitText(String.valueOf(c), 1); } public void sendDownUpKeyEvents(int k){ testKeys.accept(k); } }'''
S['android.app.Activity'] = '''public class Activity extends android.content.ContextWrapper { protected void onCreate(android.os.Bundle b){} protected void onResume(){}
 public void onWindowFocusChanged(boolean f){} public void setContentView(android.view.View v){} }'''
S['android.app.AlertDialog'] = '''public class AlertDialog { public static class Builder { public Builder(android.content.Context c){}
 public Builder setTitle(CharSequence t){return this;} public Builder setMessage(CharSequence m){return this;}
 public Builder setPositiveButton(CharSequence t, android.content.DialogInterface.OnClickListener l){return this;}
 public Builder setNegativeButton(CharSequence t, android.content.DialogInterface.OnClickListener l){return this;} public AlertDialog show(){return null;} } }'''
W = 'android.widget.'
S[W+'FrameLayout'] = '''public class FrameLayout extends android.view.ViewGroup { public FrameLayout(android.content.Context c){super(c);}
 public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams { public LayoutParams(int w, int h){super(w,h);} } }'''
S[W+'LinearLayout'] = '''public class LinearLayout extends android.view.ViewGroup { public static final int HORIZONTAL=0, VERTICAL=1; public LinearLayout(android.content.Context c){super(c);}
 public void setOrientation(int o){} public void setGravity(int g){}
 public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams { public int gravity; public float weight; public LayoutParams(int w, int h){super(w,h);} public LayoutParams(int w, int h, float wt){super(w,h);} } }'''
S[W+'ScrollView'] = 'public class ScrollView extends FrameLayout { public ScrollView(android.content.Context c){super(c);} }'
S[W+'HorizontalScrollView'] = 'public class HorizontalScrollView extends FrameLayout { public HorizontalScrollView(android.content.Context c){super(c);} }'
S[W+'Adapter'] = 'public interface Adapter { int getCount(); Object getItem(int p); long getItemId(int p); android.view.View getView(int p, android.view.View c, android.view.ViewGroup g); }'
S[W+'ListAdapter'] = 'public interface ListAdapter extends Adapter {}'
S[W+'BaseAdapter'] = 'public abstract class BaseAdapter implements ListAdapter { public void notifyDataSetChanged(){} }'
S[W+'AdapterView'] = '''public abstract class AdapterView<T extends Adapter> extends android.view.ViewGroup { public AdapterView(android.content.Context c){super(c);}
 public interface OnItemClickListener { void onItemClick(AdapterView<?> parent, android.view.View view, int position, long id); }
 public void setOnItemClickListener(OnItemClickListener l){} public void setSelection(int p){} }'''
S[W+'AbsListView'] = '''public abstract class AbsListView extends AdapterView<ListAdapter> { public AbsListView(android.content.Context c){super(c);}
 public static class LayoutParams extends android.view.ViewGroup.LayoutParams { public LayoutParams(int w, int h){super(w,h);} } }'''
S[W+'GridView'] = '''public class GridView extends AbsListView { public static final int STRETCH_COLUMN_WIDTH = 2; public GridView(android.content.Context c){super(c);}
 public void setNumColumns(int n){} public void setStretchMode(int m){} public void setVerticalSpacing(int s){} public void setHorizontalSpacing(int s){} public void setAdapter(ListAdapter a){} }'''
S[W+'TextView'] = '''public class TextView extends android.view.View { public TextView(android.content.Context c){super(c);}
 public final void setText(CharSequence t){} public void setGravity(int g){} public void setTextSize(int u, float s){} public void setTypeface(android.graphics.Typeface t){}
 public void setTextColor(int c){} public void setSingleLine(boolean b){} public void setHintTextColor(int c){} public void setAllCaps(boolean a){} public final void setHint(CharSequence h){} public void setInputType(int t){} public void setMinLines(int n){} }'''
S[W+'Button'] = 'public class Button extends TextView { public Button(android.content.Context c){super(c);} }'
S[W+'EditText'] = 'public class EditText extends TextView { public EditText(android.content.Context c){super(c);} }'
S[W+'CompoundButton'] = '''public abstract class CompoundButton extends Button { public CompoundButton(android.content.Context c){super(c);}
 public interface OnCheckedChangeListener { void onCheckedChanged(CompoundButton b, boolean checked); }
 public void setChecked(boolean c){} public void setOnCheckedChangeListener(OnCheckedChangeListener l){} }'''
S[W+'Switch'] = 'public class Switch extends CompoundButton { public Switch(android.content.Context c){super(c);} }'
S[W+'RadioButton'] = 'public class RadioButton extends CompoundButton { public RadioButton(android.content.Context c){super(c);} }'
S[W+'RadioGroup'] = '''public class RadioGroup extends LinearLayout { public RadioGroup(android.content.Context c){super(c);}
 public interface OnCheckedChangeListener { void onCheckedChanged(RadioGroup group, int checkedId); }
 public void setOnCheckedChangeListener(OnCheckedChangeListener l){} }'''
for fq, body in S.items():
    pkg, cls = fq.rsplit('.', 1)
    d = os.path.join(root, *pkg.split('.')); os.makedirs(d, exist_ok=True)
    open(os.path.join(d, cls + '.java'), 'w').write(f'package {pkg};\n{body}\n')
print(len(S), 'stub classes')
