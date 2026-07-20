package pl.lambada.songsync.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import pl.lambada.songsync.domain.model.SortOrders
import pl.lambada.songsync.domain.model.SortValues
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.defaultProviderFallbackOrder
import pl.lambada.songsync.util.set

/**
 * Parses the comma-joined blacklist string back into folder paths. Crucially filters blank entries: a stored
 * empty string would otherwise become `[""]` (size 1), making `blacklistedFolders.isNotEmpty()` spuriously true
 * even when nothing is blacklisted. Pure + internal so it's unit-testable without a DataStore.
 */
internal fun parseBlacklistedFolders(raw: String): List<String> =
    raw.split(",").filter { it.isNotBlank() }

/**
 * Parses the persisted provider fallback order (comma-joined enum names) back into a full provider list.
 * Robust against renames/removals (unknown names are dropped) and additions (providers missing from the stored
 * value — e.g. ones added in an update — are appended in default-order position). Pure + internal so it's
 * unit-testable without a DataStore.
 */
internal fun parseProviderOrder(raw: String): List<Providers> {
    val stored = raw.split(",").mapNotNull { name ->
        Providers.entries.find { it.name == name.trim() }
    }.distinct()
    return stored + defaultProviderFallbackOrder.filter { it !in stored }
}

/** Parses the persisted disabled-provider set (comma-joined enum names), dropping unknown names. */
internal fun parseDisabledProviders(raw: String): Set<Providers> =
    raw.split(",").mapNotNull { name -> Providers.entries.find { it.name == name.trim() } }.toSet()

class UserSettingsController(private val dataStore: DataStore<Preferences>) {
    // Read the ENTIRE preferences snapshot once at construction (a single blocking read) instead of one
    // runBlocking { data.first() } per field. UserSettingsController is built on the main thread in
    // MainActivity.onCreate, so the old ~17 separate blocking reads stacked up into a noticeable startup stall.
    // This isolates the startup blocking to this single point. (A fully async settings load would ripple through
    // every screen that reads these as plain state, so it's intentionally out of scope for this change.)
    private val prefs: Preferences = runBlocking(Dispatchers.IO) { dataStore.data.first() }

    var embedLyricsIntoFiles by mutableStateOf(prefs[embedKey] ?: false)
        private set

    var passedInit by mutableStateOf(prefs[passedInitKey] ?: false)
        private set

    var selectedProvider by mutableStateOf(
        // Default to LRCLib (no keys, reliable, returns synced+plain+duration). Null-safe: a value
        // stored for a since-removed provider (e.g. Musixmatch) falls back to LRCLib instead of crashing.
        Providers.entries
            .find { it.displayName == (prefs[selectedProviderKey] ?: Providers.LRCLIB.displayName) }
            ?: Providers.LRCLIB
    )
        private set

    var blacklistedFolders by mutableStateOf(
        parseBlacklistedFolders(prefs[blacklistedFoldersKey] ?: "")
    )
        private set

    // Provider fallback chain (issue #6): the full, user-arrangeable order providers are tried in, plus the set
    // the user switched off. The effective chain is [enabledProviderOrder].
    var providerOrder by mutableStateOf(parseProviderOrder(prefs[providerOrderKey] ?: ""))
        private set

    var disabledProviders by mutableStateOf(parseDisabledProviders(prefs[disabledProvidersKey] ?: ""))
        private set

    /** The providers actually tried, in the user's order. Never empty: falls back to LRCLib if all are off. */
    val enabledProviderOrder: List<Providers>
        get() = providerOrder.filterNot { it in disabledProviders }.ifEmpty { listOf(Providers.LRCLIB) }

    var hideLyrics by mutableStateOf(prefs[hideLyricsKey] ?: false)
        private set

    var includeTranslation by mutableStateOf(prefs[includeTranslationKey] ?: false)
        private set

    var includeRomanization by mutableStateOf(prefs[includeRomanizationKey] ?: false)
        private set

    var multiPersonWordByWord by mutableStateOf(prefs[multiPersonWordByWordKey] ?: true)
        private set

    var pureBlack by mutableStateOf(prefs[pureBlackKey] ?: true)
        private set

    var disableMarquee by mutableStateOf(prefs[disableMarqueeKey] ?: false)
        private set

    var sdCardPath by mutableStateOf(prefs[sdCardPathKey])
        private set

    var showPath by mutableStateOf(prefs[showPathKey] ?: false)
        private set

    var directlyModifyTimestamps by mutableStateOf(prefs[directlyModifyTimestampsKey] ?: false)
        private set

    var sortOrder by mutableStateOf(
        SortOrders.entries
            .find { it.queryName == (prefs[sortOrderKey] ?: SortOrders.ASCENDING.queryName) }!!
    )
        private set

    // Batch-download dialog toggles, remembered across runs (and app restarts) so users don't have to re-enable
    // "Embed lyrics" / "Add unsynced fallback" etc. before every batch.
    var batchSaveLrc by mutableStateOf(prefs[batchSaveLrcKey] ?: true)
        private set

    var batchEmbedLyrics by mutableStateOf(prefs[batchEmbedLyricsKey] ?: false)
        private set

    var batchAddUnsyncedFallback by mutableStateOf(prefs[batchAddUnsyncedFallbackKey] ?: false)
        private set

    var batchCorrectMetadata by mutableStateOf(prefs[batchCorrectMetadataKey] ?: false)
        private set

    var batchSkipExisting by mutableStateOf(prefs[batchSkipExistingKey] ?: true)
        private set

    // Default ON: songs a previous run already searched and found no lyrics for are left out so a rerun doesn't
    // keep asking the providers for songs that have nothing. Songs that errored out (network/IO) are still
    // retried. Turn off to re-check the empty ones too.
    var batchSkipNoLyrics by mutableStateOf(prefs[batchSkipNoLyricsKey] ?: true)
        private set

    var batchAutoTryProviders by mutableStateOf(prefs[batchAutoTryProvidersKey] ?: true)
    var batchFastMode by mutableStateOf(prefs[batchFastModeKey] ?: true)
        private set

    // Fast mode for the single-song search: race only the top providers with a short timeout. Off by default —
    // the full chain (plus the last-resort rescue) finds more, fast mode answers sooner.
    var singleFastMode by mutableStateOf(prefs[singleFastModeKey] ?: false)
        private set

    var sortBy by mutableStateOf(
        SortValues.entries
            .find { it.name == (prefs[sortByKey] ?: SortValues.TITLE.name) }!!
    )
        private set

    fun updateEmbedLyrics(to: Boolean) {
        dataStore.set(embedKey, to)
        embedLyricsIntoFiles = to
    }

    fun updatePassedInit(to: Boolean) {
        dataStore.set(passedInitKey, to)
        passedInit = to
    }

    fun updateSelectedProviders(to: Providers) {
        dataStore.set(selectedProviderKey, to.displayName)
        selectedProvider = to
    }

    fun updateBlacklistedFolders(to: List<String>) {
        dataStore.set(blacklistedFoldersKey, to.joinToString(","))
        blacklistedFolders = to
    }

    fun updateProviderOrder(to: List<Providers>) {
        dataStore.set(providerOrderKey, to.joinToString(",") { it.name })
        providerOrder = to
    }

    fun updateProviderEnabled(provider: Providers, enabled: Boolean) {
        val to = if (enabled) disabledProviders - provider else disabledProviders + provider
        dataStore.set(disabledProvidersKey, to.joinToString(",") { it.name })
        disabledProviders = to
    }

    fun updateHideLyrics(to: Boolean) {
        dataStore.set(hideLyricsKey, to)
        hideLyrics = to
    }

    fun updateIncludeTranslation(to: Boolean) {
        dataStore.set(includeTranslationKey, to)
        includeTranslation = to
    }

    fun updateIncludeRomanization(to: Boolean) {
        dataStore.set(includeRomanizationKey, to)
        includeRomanization = to
    }

    fun updateMultiPersonWordByWord(to: Boolean) {
        dataStore.set(multiPersonWordByWordKey, to)
        multiPersonWordByWord = to
    }

    fun updateDisableMarquee(to: Boolean) {
        dataStore.set(disableMarqueeKey, to)
        disableMarquee = to
    }

    fun updatePureBlack(to: Boolean) {
        dataStore.set(pureBlackKey, to)
        pureBlack = to
    }

    fun updateSdCardPath(to: String) {
        dataStore.set(sdCardPathKey, to)
        sdCardPath = to
    }

    fun updateShowPath(to: Boolean) {
        dataStore.set(showPathKey, to)
        showPath = to
    }

    fun updateDirectlyModifyTimestamps(to: Boolean) {
        dataStore.set(directlyModifyTimestampsKey, to)
        directlyModifyTimestamps = to
    }

    fun updateBatchSaveLrc(to: Boolean) {
        dataStore.set(batchSaveLrcKey, to)
        batchSaveLrc = to
    }

    fun updateBatchEmbedLyrics(to: Boolean) {
        dataStore.set(batchEmbedLyricsKey, to)
        batchEmbedLyrics = to
    }

    fun updateBatchAddUnsyncedFallback(to: Boolean) {
        dataStore.set(batchAddUnsyncedFallbackKey, to)
        batchAddUnsyncedFallback = to
    }

    fun updateBatchCorrectMetadata(to: Boolean) {
        dataStore.set(batchCorrectMetadataKey, to)
        batchCorrectMetadata = to
    }

    fun updateBatchSkipExisting(to: Boolean) {
        dataStore.set(batchSkipExistingKey, to)
        batchSkipExisting = to
    }

    fun updateBatchSkipNoLyrics(to: Boolean) {
        dataStore.set(batchSkipNoLyricsKey, to)
        batchSkipNoLyrics = to
    }

    fun updateBatchAutoTryProviders(to: Boolean) {
        dataStore.set(batchAutoTryProvidersKey, to)
        batchAutoTryProviders = to
    }

    fun updateBatchFastMode(to: Boolean) {
        dataStore.set(batchFastModeKey, to)
        batchFastMode = to
    }

    fun updateSingleFastMode(to: Boolean) {
        dataStore.set(singleFastModeKey, to)
        singleFastMode = to
    }

    fun updateSortOrder(to: SortOrders) {
        dataStore.set(sortOrderKey, to.queryName)
        sortOrder = to
    }

    fun updateSortBy(to: SortValues) {
        dataStore.set(sortByKey, to.name)
        sortBy = to
    }
}

private val embedKey = booleanPreferencesKey("embed_lyrics")
private val passedInitKey = booleanPreferencesKey("passed_init")
private val selectedProviderKey = stringPreferencesKey("provider")
private val blacklistedFoldersKey = stringPreferencesKey("blacklist")
private val providerOrderKey = stringPreferencesKey("provider_fallback_order")
private val disabledProvidersKey = stringPreferencesKey("disabled_providers")
private val hideLyricsKey = booleanPreferencesKey("hide_lyrics")
private val includeTranslationKey = booleanPreferencesKey("include_translation")
private val includeRomanizationKey = booleanPreferencesKey("include_romanization")
private val multiPersonWordByWordKey = booleanPreferencesKey("multi_person_word_by_word")
private val disableMarqueeKey = booleanPreferencesKey("marquee_disable")
private val pureBlackKey = booleanPreferencesKey("pure_black")
private val sdCardPathKey = stringPreferencesKey("sd_card_path")
private val showPathKey = booleanPreferencesKey("show_path")
private val sortOrderKey = stringPreferencesKey("sort_order")
private val sortByKey = stringPreferencesKey("sort_by")
private val directlyModifyTimestampsKey = booleanPreferencesKey("directly_modify_timestamps")
private val batchSaveLrcKey = booleanPreferencesKey("batch_save_lrc")
private val batchEmbedLyricsKey = booleanPreferencesKey("batch_embed_lyrics")
private val batchAddUnsyncedFallbackKey = booleanPreferencesKey("batch_add_unsynced_fallback")
private val batchCorrectMetadataKey = booleanPreferencesKey("batch_correct_metadata")
private val batchSkipExistingKey = booleanPreferencesKey("batch_skip_existing")
private val batchSkipNoLyricsKey = booleanPreferencesKey("batch_skip_no_lyrics")
private val batchAutoTryProvidersKey = booleanPreferencesKey("batch_auto_try_providers")
private val batchFastModeKey = booleanPreferencesKey("batch_fast_mode")
private val singleFastModeKey = booleanPreferencesKey("single_fast_mode")