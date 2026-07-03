## MusicResync v1.5.0

### Batch download, reborn
- The batch download now runs in the background. Tap "Run in background" and it keeps going with a progress notification even when you leave the app. Tap the notification to jump straight back into the live view without restarting anything.
- Before it runs in the background, a short "keep it running" guide helps you allow notifications and background activity so Android does not kill the download. The batch keeps running the whole time you follow the steps.
- Brand new full screen progress view instead of the old cramped popup. The song being searched sits front and center with its cover art, matched songs slide away as the next one arrives, and the counters animate as they climb.
- Swipe right to look back through songs the batch already handled. Each one shows whether it got synced lyrics, unsynced lyrics, or nothing, and you can tap to read the saved lyrics. Browsing never yanks you back to the current song; press "Show live" when you want to return.
- Clear tally of synced, unsynced, not found, and failed songs.

### Unsynced lyrics, on demand
- The batch now tells you how many songs have plain unsynced lyrics available even when you did not turn the fallback on. One tap on "Add all" saves them for every song that has them, and keeps adding new ones for the rest of the run.

### Remembering more
- Songs that fail to fetch now stay marked as failed with their own icon, saved across restarts, instead of blending in with songs that simply have no lyrics.
- Open a song's provider list to see which provider found the lyrics, which came up empty, and which were never tried. This is saved per song.
- The batch options you pick (embed lyrics, unsynced fallback, and the rest) are remembered between runs.

### Fixes
- Fixed the freeze when going back with the back gesture or returning to the app on large libraries.
- Fixed the thumbnail picker and lyrics matcher showing results for a different singer just because the title matched. The correct artist is now required unless the exact song length confirms the match.
- Various performance improvements for libraries with thousands of songs.
