## MusicResync v1.8.0

### Deezer: synced lyrics for the songs nobody else had
MusicResync now gets synced lyrics from **Deezer**, and asks it **first**. No account or key is needed. Deezer carries licensed, timed lyrics for a huge catalogue, including a lot of Balkan rap that LRCLib and the others simply don't have: Biba "Au" and "BIG BOSS", Gregor Magaš "Srećica", Nihil "Snob", Crni Cerak "Dlan", 38banda "START" and many more. On a real library of SnapTube rips, a rerun over the "No Lyrics" tab found synced lyrics for 32 of the first 79 songs, all of which had come up empty before.

- A Deezer result only counts when it actually has synced lyrics, so it never stops the other sources from being asked.
- Deezer's search returns an artist's whole catalogue, so its results also need a matching title. A same-length song by the same artist can't slip through.
- **Already arranged your providers?** Your order stays. Deezer is slotted in at its default spot (first), and you can move it or switch it off in Settings like any other.
- Songs an earlier batch couldn't find are **retried automatically** when a source they were never searched on is available. "Skip songs with no lyrics" now only skips songs that every current source has already seen.

### Hide songs, skip folders, pick folders (#18, #21, #22)
- **Hide songs** you never want lyrics for: select them and tap the hide icon. They're listed (and can be unhidden) under Filters → Hidden songs.
- **Folder picker** is now a full tree with tri-state checkboxes, search and song counts. A new **"Only show checked folders"** mode means new folders stay out until you add them.
- **Skip folder** during a batch run: jump past the rest of a folder without stopping the whole run.

### Now playing (#19)
An optional **Now playing** card at the top of Home shows what's playing in any app: YouTube, Spotify, your music player. Tap it for a **live lyrics view** that follows the song with the current line highlighted. Tap a line to seek, and the controls drive the playing app. You can also save lyrics for a library song straight from there. Turn it on or off in Settings (it needs notification access, which is only used to read what's playing).

### Simpler, cleaner
- **Fast matching is on by default** everywhere. Pick songs, press Start, done.
- Batch options keep the three choices that matter up top. Everything else sits under a collapsed **Advanced** section.
- Filters and Hidden songs are full pages instead of popups. Old settings that did nothing are gone.
- New selection look: picked songs become rounded cards with a tick on the cover, and the top bar groups Select all / Invert / Hide with a highlighted **download** button.
- Batch screen: a bigger cover with the spinner right on it, Stop always on screen, no "Show live" flicker as each song slides away, and songs with no lyrics no longer list the providers that were tried.
- The lyrics player's background takes its colours from the artwork, and you can choose which source to use (saved lyrics, best match, or one provider), with a preview before applying.

### Sources
- BetterLyrics falls back to its keyless upstream when its API only answers from cache.
- LyricsPlus drops two dead mirrors and keeps the three live ones.

Install over your existing app. It's signed with the same key, so it upgrades in place and keeps your settings.
