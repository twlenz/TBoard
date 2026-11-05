"""Builds the bundled dictionary: wordfreq frequencies, filtered through the
Hunspell en_US spelling list so misspellings like 'teh' are not treated as words."""
import re, sys, os
from wordfreq import top_n_list, zipf_frequency
from spylls.hunspell import Dictionary
import better_profanity

hun = Dictionary.from_files('/usr/share/hunspell/en_US')
prof_path = os.path.join(os.path.dirname(better_profanity.__file__), 'profanity_wordlist.txt')
bad = set(l.strip().lower() for l in open(prof_path) if l.strip())

chat = set("""lol omg lmao btw idk tbh imo fyi brb ttyl np thx pls plz ya yep nope yeah nah gonna wanna gotta
kinda sorta lemme gimme ok okay wifi app apps emoji emojis selfie selfies online offline email emails
texting googled vlog blog blogs podcast podcasts meme memes hashtag smartphone smartphones lmk asap""".split())

ok = re.compile(r"^[a-z]+('[a-z]+)?$")
out = {}
for w in top_n_list('en', 90000):
    if not ok.match(w): continue
    if len(w) == 1 and w not in ('a', 'i'): continue
    z = zipf_frequency(w, 'en')
    if z < 1.9: continue
    if w == 'i' or w.startswith("i'"):
        form = 'I' + w[1:]
    elif w in chat or hun.lookup(w):
        form = w
    elif hun.lookup(w.capitalize()):
        form = w.capitalize()
    elif len(w) <= 5 and hun.lookup(w.upper()):
        form = w.upper()
    else:
        continue
    out[w] = (form, z)
    if len(out) >= 65000: break

res = sorted(out.items(), key=lambda kv: -kv[1][1])
with open(sys.argv[1], 'w') as f:
    for w, (form, z) in res:
        f.write(f"{form}\t{z:.2f}\t{1 if w in bad else 0}\n")
print(len(res), 'words;', sum(1 for w, _ in res if w in bad), 'flagged')
