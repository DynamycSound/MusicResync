## MusicResync v1.6.1

### Way faster lyrics search (fixes #9)
Searching for lyrics could take **up to ~10 minutes** when a song was only available on a provider late in the chain (like QQ Music): every provider was tried one after another, each with its own retries and long network timeouts.

- **All providers are now queried at the same time.** The total wait is roughly the time of the fastest provider that has your lyrics — not the sum of every slow one before it.
- **Per-provider time budget (25s).** A hung or unresponsive provider is skipped instead of stalling the whole search.
- **Snappier retries.** Failed requests still retry with exponential backoff, just with shorter delays (0.5s → 1s → 2s).
- **Tighter network timeouts** (12s per request, 6s connect) so a dead provider fails fast.

Your provider order from Settings still decides which match wins when several providers find the song, and requests to each individual provider remain sequential and politely delayed — so rate limits are respected exactly as before. Both the single-song search and the batch download benefit.

Install over your existing app — it's signed with the same key, so it upgrades in place.
