## MusicResync v1.6.2

### Batch matching now finds what single-song search finds (fixes reports of "it works when I open the song, but not in batch")
The batch downloader was missing two things the single-song screen already had, which made it fail on songs like **Ariana Grande - Focus** (artist tag `Unknown`), **D-Devils - 6th Gate**, and **Tyler, The Creator - Tamale**:

- **Every provider hit is now also scored against the parsed filename/title view of the track, not just the raw tags.** A junk tag like title `"Ariana Grande - Focus"` / artist `"Unknown"` used to make the scorer reject the correct hit outright (it saw a weak title match and a "disagreeing" artist); it's now also compared against the cleaned `Focus` / `Ariana Grande` reading, which matches. A safety guard still requires an exact runtime match before an artist-less reading can carry a result to auto-accept, so a same-titled song by the wrong artist still can't sneak through.
- **Batch now runs the same last-resort rescue** (canonicalizing a messy name via iTunes/Deezer, then re-querying) that only the single-song screen had before — this was the actual reason a song could be found by opening it manually but not during a batch run. Rescues are still validated against duration/title evidence and are always saved as "review", never silently auto-accepted.
- Placeholder artist tags (`Unknown`, `Various Artists`, `<unknown>`, …) are now treated as missing information instead of as evidence that contradicts a correct match.
- Filenames with a `(feat. ...)` clause followed by junk (e.g. `Biba - Harli Kvin (feat. AV47) [Official Audio] _420(MP3_320K).mp3`) are now parsed correctly, and trailing junk numbers left over from ripper suffixes are stripped for an extra fallback query.
- Rescued matches whose album art visually matches your file's embedded cover get a small confidence boost — a *different* cover is never held against a match, since re-releases and singles legitimately use different art.

### Fast mode (new, opt-in)
A new toggle in Settings races only the two fastest providers with a short timeout for single-song search — quicker answers at a small cost to thoroughness. Off by default; the full search remains the default everywhere.

### Fixed: lyrics preview showing wrong state / stuck loading (#11)
The synced-lyrics player and the batch "tap to view lyrics" preview only ever checked for a sidecar `.lrc` file, so a song with lyrics *embedded in the audio tags* looked like it had none — the player said "no lyrics found" and the batch preview spun forever. Both now also check embedded lyrics and always resolve to a real result.

### Removed redundant menu options (#12)
The home screen's three-dot menu now only has Settings — "batch download" already has its own button, and the lyrics-provider order lives in Settings, so the duplicate entries are gone.

### More honest provider status
In the single-song screen's provider dropdown, a provider that found usable lyrics but simply wasn't the best match is now shown as "found lyrics" instead of a red X. Only providers that were actually queried can now be marked as having failed — ones skipped by an early match stay "not tried yet".

### Polish
Saving lyrics (.lrc or embedded) now has a proper success animation instead of an instant text swap.

Install over your existing app — it's signed with the same key, so it upgrades in place.
