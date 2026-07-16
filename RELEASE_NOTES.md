## MusicResync v1.6.0

### New lyrics providers
- **LyricsPlus**: word-by-word synced lyrics from the community LyricsPlus service (6 mirrors tried in order, remembering the last one that worked).
- **BetterLyrics**: Apple-style TTML lyrics, converted straight into word-by-word timing.
- Both slot into the normal provider list and support the multi-person word-by-word format.

### Provider order, your way
- New **Provider order** section in Settings: drag providers into the order you want them tried, and untick any you don't want queried at all. Both the single-song search and the batch download now walk that exact chain, one by one, until they find a match — instead of always falling back to a fixed order.

### Fixed: lyrics embedded in the file weren't recognized
- The home page's lyrics detector only checked for a sidecar `.lrc` file. Songs with lyrics embedded directly in their tags (via "Embed lyrics") were showing up as "missing lyrics" even though they weren't. The detector now reads embedded tags too, so those songs correctly show as having lyrics.

### Batch download, cleaner
- The batch download setup is now a full page instead of a cramped popup, with a clear "Start" button.
- On the progress screen, the Synced / Unsynced / Not found / Failed counters are now tappable — each opens a drawer listing exactly which songs landed there. It opens at half the screen and slides up to full height.

Install over your existing app — it's signed with the same key, so it upgrades in place.
