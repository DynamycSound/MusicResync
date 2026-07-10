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
import pl.lambada.songsync.util.set

/**
 * Parses the comma-joined blacklist string back into folder paths. Crucially filters blank entries: a stored
 * empty string would otherwise become `[""]` (size 1), making `blacklistedFolders.isNotEmpty()` spuriously true
 * even when nothing is blacklisted. Pure + internal so it's unit-testable without a DataStore.
 */
internal fun parseBlacklistedFolders(raw: String): List<String> =
    raw.split(",").filter { it.isNotBlank() }

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

    // Default ON: songs that failed a previous run (rate limits, no usable match) are left out so a rerun doesn't
    // keep hammering them. Turn off to retry the failed ones.
    var batchSkipPreviouslyFailed by mutableStateOf(prefs[batchSkipPreviouslyFailedKey] ?: true)
        private set

    var batchAutoTryProviders by mutableStateOf(prefs[batchAutoTryProvidersKey] ?: true)
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

    fun updateBatchSkipPreviouslyFailed(to: Boolean) {
        dataStore.set(batchSkipPreviouslyFailedKey, to)
        batchSkipPreviouslyFailed = to
    }

    fun updateBatchAutoTryProviders(to: Boolean) {
        dataStore.set(batchAutoTryProvidersKey, to)
        batchAutoTryProviders = to
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
private val batchSkipPreviouslyFailedKey = booleanPreferencesKey("batch_skip_previously_failed")
private val batchAutoTryProvidersKey = booleanPreferencesKey("batch_auto_try_providers")