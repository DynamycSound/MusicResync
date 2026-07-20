## MusicResync v1.7.0

### Fast mode, fixed and promoted — now the recommended way to batch download
Fast mode was silently searching only your top **two** providers — that's why songs like **"Ili Ili"** and **"Peta Brzina"** (which are on LRCLib, synced) came back "not found" in fast batch runs while the provider that has them never even got asked. Fast mode now races **all** enabled providers in parallel with tight time budgets: same providers, same candidate parsing, same precision guards — the speed comes from not waiting on slow servers, not from skipping sources. The toggle now also lives at the top of the **batch download options**, marked *Recommended*, and applies to batch runs.

### Lyrics player actually follows the song now
The real cause of the "player stuck at the first line" bug: when opening a song from Home, the lyrics load in the background — and the highlight logic kept watching the initial *empty* lyrics list forever, so neither the highlight nor the scroll ever moved. Fixed. Word-by-word files (`<00:40.49>` inline stamps) also no longer show those raw stamps as text — and files carrying *only* word stamps now play instead of showing nothing.

### The "200" fluke, and the class it belongs to, is closed
"200" by Ourmoney got Russian lyrics from a same-*length* "200" by a different act — a runtime coincidence was still enough to override a disagreeing artist. In batch, it isn't anymore: a match whose artist agrees with **none** of the file's artist readings must now be positively confirmed by the file's cover art, or it's skipped. (The correct "200" exists on LRCLib as unsynced — enable "Add unsynced if no synced" to pick it up.)

### Cyrillic and Latin finally speak the same language
Provider entries written in Cyrillic ("Секси") now compare equal to their Latin spelling ("Seksi") instead of counting as strangers — matching now works across scripts in both directions, for artists and titles.

### More recovered names
- **'Summer Cem - "TMM TMM"'** — quotes in a title no longer break provider search (the song is on LRCLib, synced).
- Channel-artist and collab-splitting fixes from 1.6.4 now actually reach every provider thanks to the fast-mode fix.

### Honest about the rest
We verified the missing Serbian catalogue directly: QQ Music *indexes* many of these songs (Prazan Disko, Volim Pare…) but has **no lyric content** for them, Genius has no usable public API, and tekstovi.net has no machine-readable search. Songs that no catalogue carries still fail honestly — no fluke lyrics, ever.

**Samsung Music note:** Samsung's player only reads *synced* `.lrc` sidecars and *embedded* tags. If you want unsynced lyrics visible there, enable **Embed lyrics in file** in batch options — a plain text `.lrc` alone won't show up.

Install over your existing app — it's signed with the same key, so it upgrades in place.
