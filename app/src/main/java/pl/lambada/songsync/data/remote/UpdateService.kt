package pl.lambada.songsync.data.remote

import android.content.Context
import kotlinx.coroutines.flow.flow
import pl.lambada.songsync.data.remote.github.GithubAPI
import pl.lambada.songsync.domain.model.Release
import pl.lambada.songsync.util.ext.getVersion

class UpdateService {

    /**
     * Checks for updates by comparing the latest release version with the current version.
     * @param context The context of the application.
     * @return A flow emitting the update state.
     */
    fun checkForUpdates(context: Context) = flow {
        emit(UpdateState.Checking)

        try {
            val latest = GithubAPI.getLatestRelease()
            val isUpdate = isNewerRelease(context, latest)

            emit(
                if (isUpdate)
                    UpdateState.UpdateAvailable(latest)
                else
                    UpdateState.UpToDate
            )

        } catch (e: Exception) {
            emit(UpdateState.Error(e))
        }
    }

    /**
     * Checks if the latest release is newer than the current version.
     * @param context The context of the application.
     * @param latestRelease The latest release from the GitHub API.
     * @return True if the latest release is newer, false otherwise.
     */
    private fun isNewerRelease(context: Context, latestRelease: Release): Boolean {
        return compareVersions(latestRelease.tagName, context.getVersion()) > 0
    }
}

/**
 * Compares two dotted version strings segment-by-segment (e.g. "v1.10.0" vs "1.9.3"). Returns >0 if
 * [a] is newer than [b], <0 if older, 0 if equal. Fixes the old `"1.10.0".replace(".","").toInt()`
 * bug, which collapsed 1.10.0 -> 1100 and wrongly ranked it below 1.9.3 -> 193.
 */
internal fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.trim()
        .removePrefix("v").removePrefix("V")
        .split('.', '-')
        .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/**
 * Defines the state of the update check.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class UpdateAvailable(val release: Release) : UpdateState
    data class Error(val reason: Throwable) : UpdateState
}