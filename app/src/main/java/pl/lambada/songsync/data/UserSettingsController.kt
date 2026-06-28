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