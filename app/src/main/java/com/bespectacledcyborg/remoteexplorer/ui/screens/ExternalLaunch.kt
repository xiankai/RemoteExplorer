package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.bespectacledcyborg.remoteexplorer.data.PLAYBACK_TYPES
import com.bespectacledcyborg.remoteexplorer.data.views.ExternalPlaybackService
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import kotlinx.coroutines.launch
import java.net.URLConnection

@Composable
fun rememberExternalLauncher(explorerViewModel: ExplorerViewModel): (name: String, path: String) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current as? ComponentActivity
    var pendingPlaybackName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlaybackPath by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val name = pendingPlaybackName
        val path = pendingPlaybackPath
        pendingPlaybackName = null
        pendingPlaybackPath = null
        if (name == null || path == null) return@rememberLauncherForActivityResult
        activity.afterResumeStopsPlaybackService {
            handOffToExternalApp(context, name, explorerViewModel.getUrl(path))
        }
    }
    return { name, path ->
        val isPlayback = Utils.guessIconType(name) in PLAYBACK_TYPES
        if (isPlayback && !context.canPostNotifications()) {
            pendingPlaybackName = name
            pendingPlaybackPath = path
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            handOffToExternalApp(context, name, explorerViewModel.getUrl(path))
        }
    }
}

internal fun ComponentActivity?.afterResumeStopsPlaybackService(block: () -> Unit) {
    if (this == null) return block()
    lifecycleScope.launch { withResumed(block) }
}

private fun Context.canPostNotifications(): Boolean =
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun handOffToExternalApp(context: Context, name: String, url: String) {
    val mimeType = URLConnection.guessContentTypeFromName(name)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(url.toUri(), mimeType)
    try {
        context.startActivity(intent)
        if (Utils.guessIconType(name) in PLAYBACK_TYPES) {
            ExternalPlaybackService.start(context, name)
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No application found for file type: $mimeType", Toast.LENGTH_SHORT).show()
    }
}
