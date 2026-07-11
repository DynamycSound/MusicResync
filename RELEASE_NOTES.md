## MusicResync v1.5.3

### New
- Completion notification: when a batch download finishes (or stops early from rate limiting), you now get a notification with the full breakdown: synced lyrics, plain lyrics, songs with no lyrics found, errors, skipped songs, and how many plain lyrics are waiting behind "Add all". Tap it to jump straight to the results. It lives on its own "Batch results" channel, so you can tune or mute it separately from the quiet progress notification.
- Time-left estimate: the ongoing progress notification now shows a rough "about N min left" once the run's pace is known.
- The Done screen shows how long the whole run took ("Finished in 4 min 12 sec").
- Opening the results screen clears the summary notification automatically, so nothing stale sits in the shade.

### Hotfix
- Fixed what the batch "skip" option targets. It is now "Skip songs with no lyrics" and it leaves out songs a previous run already searched and found nothing for, so a rerun stops asking the providers for songs that have nothing. Songs that errored out (network or write problems) are still retried, since those can work on a second try. On by default, and songs that were never tried yet always run. In 1.5.2 this skipped the errored songs and kept re-checking the empty ones, which was backwards.
