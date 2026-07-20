## MusicResync v1.6.3 — hotfix

### Fixed: wrong lyrics saved for songs with short, generic titles
v1.6.2's looser matching (which judges hits against the parsed filename, not just the raw tags) opened a precision hole: a song whose title matches some unrelated track exactly — "PETROV - RARI" getting the German "Rari" by lil doggo, "Numero - BMW" getting "BMW" by Cecilio G., "UKIC X PETROV - A TI?" getting "A Ti" by Dyango, "QuESt - Automatic" getting a Japanese "Automatic (Live)" — could ride in on the title alone, because the artist-less view of the track had nothing left to object to the wrong singer.

New **wrong-singer veto**: whenever your file gives the matcher *any* artist guess (from the tags or the parsed filename) and a provider's result names an artist that agrees with **none** of those guesses, the result is rejected — across the normal search, the plain-lyrics fallback, and the last-resort rescue. An exact runtime match still overrides a disagreeing artist (that trust is what makes junk-tagged rips matchable at all), and a result with no artist at all is unaffected. A provider artist written in a different script (e.g. CJK vs. a Latin filename) now counts as disagreeing too.

### Fixed: lyrics player not following the song / janky scrolling
The synced-lyrics player (the adjust-timing screen) only scrolled once the highlighted line left a visibility window, which looked like the list wasn't following the song at all — and then it lurched a whole screen at a time. It now glides continuously: each new line smoothly eases the list so the active line settles just above centre, Samsung-Music style. Seeks and taps on the progress bar still jump straight to the right spot.

### Renamed leftovers
All user-facing "SongSync" texts now say **MusicResync** — including the `[by:]` tag written into generated .lrc files, the settings/about texts, and the welcome screen (all languages). The credit to the original SongSync project by Lambada10 remains, as it should.

Install over your existing app — it's signed with the same key, so it upgrades in place.
