package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lambada.songsync.data.parseBlacklistedFolders

/** M12-A: empty/blank blacklist strings must parse to an empty list, never [""]. */
class BlacklistParsingTest {

    @Test
    fun `empty string parses to empty list`() {
        assertEquals(emptyList<String>(), parseBlacklistedFolders(""))
    }

    @Test
    fun `blank-only entries are dropped`() {
        assertEquals(emptyList<String>(), parseBlacklistedFolders(",  ,"))
    }

    @Test
    fun `real folders survive and blanks are filtered`() {
        assertEquals(
            listOf("/storage/Music", "/storage/Podcasts"),
            parseBlacklistedFolders("/storage/Music,,/storage/Podcasts")
        )
    }
}
