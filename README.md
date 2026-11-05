# TBoard

An Android keyboard (Android 6.0 and newer) that does everything on the phone: tap typing with autocorrect, swipe typing, next-word suggestions, an emoji panel, light/dark/black themes, and learning from your typing. The app requests **no permissions at all**, including no internet access, and has cloud backup turned off.

## Getting the APK

Pick one:

**A. Android Studio (any computer).** Open this folder in Android Studio, let it sync, then *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The APK appears under `app/build/outputs/apk/`. Or from a terminal with the Android SDK installed: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

**B. GitHub, no Android tools needed.** Every push to `main` makes GitHub build the APK and post it under the repository's **Releases** (`build-N`), where you can download it on your phone. Pushes to other branches and pull requests are built and tested too, but not released.

Each push also runs the keyboard on an Android emulator and saves screenshots of it to the `screenshots` branch (`.github/workflows/screenshots.yml`).

## Installing and turning it on

1. Open the APK on the phone and allow installing from that source when Android asks.
2. Open **TBoard** from the app drawer.
3. Tap **Turn on TBoard**, enable it in the list, and accept Android's standard warning (it says this for every third-party keyboard).
4. Tap **Switch to TBoard**.

### Signing: do this once, before installing

Android only lets an APK replace an installed app if both are signed with the same key. This repository is public, and so is its test key (`app/debug.keystore`). Anyone could sign a different app with it, and the phone would accept it as a TBoard update. For a keyboard, which sees everything typed, that matters. So releases should be signed with a private key:

1. On github.com, open this repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
2. Name: `LOCALBOARD_KEY_PASSWORD`. Value: a long random password, at least 16 characters (a password manager can make one). Nobody, including GitHub's staff, can read it back; you don't need to remember it, but don't change or delete it later.
3. Push anything to `main` (or run the "Build APK" workflow by hand). That build creates the private key, commits it encrypted with your password as `app/release.keystore`, and signs the release with it. Its release notes say "Signed with the private TBoard key".

Releases made without the secret are named "(test key)". An APK signed with one key can't update an app installed from the other: uninstall TBoard first (this erases what it learned).

Each build has a higher version number than the last, so Android refuses to install an older build over a newer one.

### Safety notes

- The app has no permissions at all: no internet, storage, contacts or anything else. It can only read and write its own private folder, and uninstalling it removes only that folder.
- It doesn't run before the phone's first unlock after a restart (it isn't "direct boot aware"), so Android uses your phone's own keyboard for that unlock. You can't be locked out by it.
- Keep your phone's original keyboard enabled. If TBoard ever misbehaves, long-press the space bar (or use the keyboard button in the navigation bar) to switch back.

## Using it

- **Swipe** across letters to type a word. Swiped words get spaces between them automatically. Backspace deletes a swiped word letter by letter. The suggestion bar shows alternatives; tap one to swap it in.
- **Autocorrect** happens on space or punctuation, never on Enter: Enter sends or starts a new line with the word exactly as typed (and a typo kept that way isn't learned as a word). The suggestion bar shows the correction in bold and what you typed in quotes; tap the quoted one to keep your spelling. Backspace right after a correction restores what you typed, and the keyboard then stops correcting that word.
- **Words to keep** (in the app's settings): add names, slang or anything autocorrect keeps changing. Words on the list are never autocorrected, are suggested as you type, and stay when you clear learned data. Words you kept by undoing an autocorrection appear there too, so you can remove them.
- **Fixing a word later teaches it.** Backspacing into a word, selecting it, or tapping inside it lets you edit it. If you change a misspelling into a real word ("hte" → "hate"), autocorrect makes that same change by itself from then on. If you change an autocorrection back to what you typed, it stops making that correction.
- **Emoji:** tap the smiley key. The magnifier next to ABC searches emoji by name ("heart", "thumbs up"); the letter keys type into the search box. Emoji the phone can't display are hidden.
- **Long-press** a top-row letter for its number, a vowel or certain consonants (s, c, n, z, l) for accents, the period for more punctuation, and the comma for settings.
- **Long-press space** to switch keyboards; **slide on space** to move the cursor.
- **Double-tap shift** for caps lock.
- **Space on the number page goes back to the letters**, except in number-only fields (phone numbers, PINs, dates), which stay on the numbers.
- **Double-space period** is off by default. When it's on, two spaces right after a backspace never add a period.

All behaviors have their own switches in settings: autocorrect, backspace-undoes-autocorrect, double-space period, auto-capitalize, smart punctuation spacing, swipe typing, swipe trail, spaces between swiped words, suggestion bar, next-word suggestions, learning, offensive-word blocking, space-bar cursor, number hints, key preview, vibration and sound. Changes apply immediately. Off by default: double-space period, offensive-word blocking, vibration and sound.

## What it learns (all stored in the app's private storage)

- Misspellings you fixed by hand, and what you changed them to.
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

Word frequencies: wordfreq by Robyn Speer, data CC BY-SA 4.0. Spelling filter: Hunspell en_US (SCOWL). Offensive-word list: better_profanity (MIT). Emoji list and search keywords: Unicode emoji data and CLDR annotations (Unicode License v3).
