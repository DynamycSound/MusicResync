package pl.lambada.songsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifySecrets
import pl.lambada.songsync.ui.Navigator
import pl.lambada.songsync.ui.theme.MusicResyncTheme
import pl.lambada.songsync.util.batch.BatchDownloadController
import pl.lambada.songsync.util.batch.BatchDownloadService
import pl.lambada.songsync.util.dataStore
import java.io.File

/**
 * The main activity of the MusicResync app.
 */
class MainActivity : ComponentActivity() {
    private val lyricsProviderService = LyricsProviderService()

    @SuppressLint("SuspiciousIndentation")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // fixes weird system bars background upon app loading
        enableEdgeToEdge()

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            view.setPadding(0, 0, 0, 0)
            insets
        }

        val dataStore = this.dataStore
        val userSettingsController = UserSettingsController(dataStore)
        SpotifySecrets.init(applicationContext)
        checkOrCreateDownloadSubFolder()
        createNotificationChannel()
        handleBatchIntent(intent)

        setContent {
            val navController = rememberNavController()

            LaunchedEffect(Unit) {
                // Warm the Spotify secret cache in the background. Never blocks launch and never
                // surfaces an error: Spotify is one optional provider and its token is fetched
                // lazily on first use, not here.
                SpotifySecrets.refresh()
            }

            MusicResyncTheme(pureBlack = userSettingsController.pureBlack) {
                // check in case user revoked permissions later
                if (userSettingsController.passedInit)
                    CheckForPermissions(
                        userSettingsController = userSettingsController
                    )

                Surface(modifier = Modifier.fillMaxSize()) {
                    Navigator(
                        navController = navController,
                        userSettingsController = userSettingsController,
                        lyricsProviderService = lyricsProviderService
                    )
                }
            }
        }
    }

    override fun onResume() {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(2) // "Done" notification
        super.onResume()
    }

    // singleTask: a tap on the batch notification lands here when the activity already exists. The activity
    // (and the running batch) is reused as-is; we only ask the navigator to show the batch screen.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleBatchIntent(intent)
    }

    private fun handleBatchIntent(intent: Intent?) {
        if (intent?.action == BatchDownloadService.ACTION_OPEN_BATCH) {
            BatchDownloadController.requestOpen()
        }
    }
}

@Composable
@OptIn(ExperimentalPermissionsApi::class)
private fun MainActivity.CheckForPermissions(
    userSettingsController: UserSettingsController
) {
    // All-Files-Access (MANAGE_EXTERNAL_STORAGE / isExternalStorageManager) applies from API 30 (Android 11 = R)
    // upward — same threshold the init flow uses (InitScreenViewModel / AllFilesAccess). The previous `> R` left
    // Android 11 itself out, wrongly routing it to the legacy runtime-permission branch.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            userSettingsController.updatePassedInit(false)
        }
    } else {
        val permissions = rememberMultiplePermissionsState(
            permissions = listOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
        if (!permissions.allPermissionsGranted) {
            userSettingsController.updatePassedInit(false)
        }
    }
}

private fun checkOrCreateDownloadSubFolder() {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
    )

    val musicResyncDir = File(downloadsDir, "MusicResync")

    if (!musicResyncDir.exists()) musicResyncDir.mkdir()
}

private fun Activity.createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = getString(R.string.batch_download_lyrics)
        val channelName = getString(R.string.batch_download_lyrics)
        val channelDescription = getString(R.string.batch_download_lyrics)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.description = channelDescription

        notificationManager.createNotificationChannel(channel)
    }
}
