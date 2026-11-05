package dev.tboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.tboard.engine.Dictionary;
import dev.tboard.engine.Lexicon;
import dev.tboard.engine.UserModel;

/**
 * One copy of the dictionary and the learned model per app process, shared by
 * the keyboard and the settings screen. The learned model lives in the app's
 * private storage (user_model.tsv); nothing is ever sent anywhere.
 */
final class Engine {
    private static final String TAG = "TBoard";
    private static final String MODEL_FILE = "user_model.tsv";
    private static Engine instance;

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> waiting = new ArrayList<>();
    private boolean loading;
    private int clearGeneration;

    /** Non-null once loaded. Only touched on the main thread. */
    Lexicon lexicon;

    static synchronized Engine get(Context ctx) {
        if (instance == null) instance = new Engine(ctx.getApplicationContext());
        return instance;
    }

    private Engine(Context app) {
        this.app = app;
    }

    /** Runs {@code ready} on the main thread once the dictionary and model are loaded. */
    void whenReady(Runnable ready) {
        if (lexicon != null) {
            ready.run();
            return;
        }
        waiting.add(ready);
        if (loading) return;
        loading = true;
        final int gen = clearGeneration;
        io.execute(() -> {
            Dictionary dict = null;
            UserModel user = new UserModel();
            try (InputStream in = app.getAssets().open("en_words.tsv")) {
                dict = Dictionary.load(in);
            } catch (Exception e) {
                Log.e(TAG, "dictionary load failed", e);
            }
            File f = new File(app.getFilesDir(), MODEL_FILE);
            if (f.exists()) {
                try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                    user.load(r);
                } catch (Exception e) {
                    Log.e(TAG, "model load failed", e);
                }
            }
            final Dictionary d = dict;
            main.post(() -> {
                loading = false;
                if (d == null) return;
                boolean cleared = gen != clearGeneration;
                if (cleared) user.clear();
                lexicon = new Lexicon(d, user);
                if (cleared) save();
                ArrayList<Runnable> run = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : run) r.run();
            });
        });
    }

    /** Writes the learned model in the background if it changed. Call on the main thread. */
    void save() {
        if (lexicon == null || !lexicon.user.isDirty()) return;
        final String data = lexicon.user.serialize();
        io.execute(() -> {
            File dir = app.getFilesDir();
            File tmp = new File(dir, MODEL_FILE + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(data);
            } catch (Exception e) {
                Log.e(TAG, "model save failed", e);
                return;
            }
            if (!tmp.renameTo(new File(dir, MODEL_FILE))) Log.e(TAG, "model rename failed");
        });
    }

    /** Forgets everything learned except the "Words to keep" list. Call on the main thread. */
    void clearUserData() {
        clearGeneration++;
        if (lexicon != null) {
            lexicon.user.clear();
            save();
        }
        // if still loading, the load clears it and saves (below)
    }

    /** The "Words to keep" list, or null while loading. Call on the main thread. */
    java.util.List<String> keptWords() {
        return lexicon == null ? null : lexicon.user.keptWords(lexicon.dict);
    }

    void keepWord(String word) {
        if (lexicon == null) return;
        lexicon.user.keep(word);
        save();
    }

    void unkeepWord(String word) {
        if (lexicon == null) return;
        lexicon.user.unkeep(word);
        save();
    }

    int learnedWordCount() {
        return lexicon == null ? -1 : lexicon.user.learnedWordCount();
    }
}
