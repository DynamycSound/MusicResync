## MusicResync v1.5.3

### Hotfix
- Fixed what the batch "skip" option targets. It is now "Skip songs with no lyrics" and it leaves out songs a previous run already searched and found nothing for, so a rerun stops asking the providers for songs that have nothing. Songs that errored out (network or write problems) are still retried, since those can work on a second try. On by default, and songs that were never tried yet always run. In 1.5.2 this skipped the errored songs and kept re-checking the empty ones, which was backwards.
