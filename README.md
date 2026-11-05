# LocalBoard

An Android keyboard (Android 10+) that does everything on the phone: tap typing with autocorrect, swipe typing, next-word suggestions, an emoji panel, light/dark/black themes, and learning from your typing. The app requests **no permissions at all**, including no internet access, and has cloud backup turned off.

## Getting the APK

Pick one:

**A. Android Studio (any computer).** Open this folder in Android Studio, let it sync, then *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The APK appears under `app/build/outputs/apk/`. Or from a terminal with the Android SDK installed: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

**B. GitHub, no Android tools needed.** Every push to `main` makes GitHub build the APK and post it under the repository's **Releases** (`build-N`), where you can download it on your phone. Pushes to other branches and pull requests are built and tested too, but not released.

Each push also runs the keyboard on an Android emulator and saves screenshots of it to the `screenshots` branch (`.github/workflows/screenshots.yml`).

## Installing and turning it on

1. Open the APK on the phone and allow installing from that source when Android asks.
2. Open **LocalBoard** from the app drawer.
3. Tap **Turn on LocalBoard**, enable it in the list, and accept Android's standard warning (it says this for every third-party keyboard).
4. Tap **Switch to LocalBoard**.

Every build is signed with the same key (`app/debug.keystore`), so a new APK installs over the old one and keeps what the keyboard has learned. That key is a debug key and not secret; it is fine for personal sideloading, not for a store release.

## Using it

- **Swipe** across letters to type a word. Swiped words get spaces between them automatically; one backspace removes a whole swiped word. The suggestion bar shows alternatives; tap one to swap it in.
- **Autocorrect** happens on space or punctuation. The suggestion bar shows the correction in bold and what you typed in quotes; tap the quoted one to keep your spelling. Backspace right after a correction restores what you typed, and the keyboard then stops correcting that word.
- **Long-press** a top-row letter for its number, a vowel or certain consonants (s, c, n, z, l) for accents, the period for more punctuation, and the comma for settings.
- **Long-press space** to switch keyboards; **slide on space** to move the cursor.
- **Double-tap shift** for caps lock.

All behaviors have their own switches in settings: autocorrect, backspace-undoes-autocorrect, double-space period, auto-capitalize, smart punctuation spacing, swipe typing, swipe trail, spaces between swiped words, suggestion bar, next-word suggestions, learning, offensive-word blocking, space-bar cursor, number hints, key preview, vibration and sound. Changes apply immediately.

## What it learns (all stored in the app's private storage)

- Words you use that aren't in the dictionary. A word you type twice, pick from the bar, or restore with backspace stops being autocorrected.
- How often you use each word, which raises it in suggestions and in swipe results.
- Which words you type after which, for next-word suggestions (a pair appears after you've used it twice).
- Where your taps land on each key, so autocorrect adjusts to your aim.

Password fields get plain typing: no autocorrect, suggestions, capitalization changes or double-space period. It never learns in password fields, email/URL fields, or fields that ask for no personalized learning (incognito modes). *Settings → Clear everything learned* erases it.

## How it works

- `engine/TapDecoder` scores every plausible dictionary word as *log frequency + context − edit cost*, where substituting a letter costs more the farther your tap was from that key (a Gaussian touch model centered on your learned tap offsets). Swapped, doubled and missing letters have their own costs.
- `engine/SwipeDecoder` keeps words whose first and last letters are near the start and end of the swipe. It aligns each word's letters to the path in order, compares the path's shape to the ideal path between the word's keys, and adds the word's frequency.
- `engine/UserModel` holds what's learned; it is saved as a small text file.
- The dictionary (`assets/en_words.tsv`, 61,823 words) was built by `tools/make_dict.py`: frequencies from wordfreq, filtered through the Hunspell en_US spelling list so misspellings like "teh" don't count as words. Emoji lists come from `tools/make_emoji.py`.

## Known limits

- Swipe accuracy is below Gboard's; Gboard uses neural models trained on very large amounts of typing data. In simulated swipes, the intended word came first about 94% of the time and was in the top three about 98% of the time; real swipes are messier than simulated ones.
- English only. No voice typing, clipboard history or GIFs.
- `targetSdk` is 34 so Android 15's edge-to-edge layout rules don't apply to the keyboard window. This matters only for publishing on the Play Store, which this app isn't meant for.

Word frequencies: wordfreq by Robyn Speer, data CC BY-SA 4.0. Spelling filter: Hunspell en_US (SCOWL). Offensive-word list: better_profanity (MIT).
