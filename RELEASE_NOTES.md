## MusicResync v1.6.4

### New: cover-art identification — the file's own thumbnail now tells us who sings it
A file like **"Bounce"** with artist "Unknown" is textually indistinguishable from dozens of other songs called Bounce — that's how it got Russian lyrics from a completely different track. But its embedded cover art *is* distinguishable. When a file gives the matcher no usable artist at all, MusicResync now searches iTunes/Deezer widely with the bare title, compares each result's album art against the file's own cover, and treats a visual match (plus a compatible runtime) as the file's real identity. For the reported "Bounce", that discovers **Voyage** — whose synced lyrics are right there on LRCLib — and the search leads with "Voyage Bounce" instead of a blind "Bounce".

The same check now guards against runtime coincidences: a result whose artist agrees with *none* of the file's artist readings (like **"CHECK"** by WAV3POP getting Portuguese lyrics from a same-length "Check" by The Boy) gets a second opinion from the cover before being trusted — if the art identifies a different artist, the impostor is skipped.

### Messy names that now resolve (verified against the live catalogues)
- **"DEVITO - FLEX ????"** — runs of `?` left by characters the filesystem couldn't store are stripped from queries; the track ("Taj flex" by Devito) is on LRCLib.
- **"CRNI CERAK - CC #2 (JUŽNI VETAR 2022)"** — bracketed junk the noise list doesn't know now falls back to a bare-title query; "CC #2" by Crni Cerak is on LRCLib.
- **"GRCA X FOX - BELE ŠARE"** — *every* collab artist is now tried, not just the first: the track is indexed under Fox (with GRCA as the guest), and Fox no longer counts as a stranger to the wrong-singer veto.
- **"CHECK" by "WAV3POP - Topic"** — YouTube channel decorations (" - Topic", " TV", " Official"…) are stripped into an extra artist reading, so the real artist name reaches the providers.

Songs genuinely absent from every lyrics catalogue still fail honestly — no fluke lyrics.

Install over your existing app — it's signed with the same key, so it upgrades in place.
