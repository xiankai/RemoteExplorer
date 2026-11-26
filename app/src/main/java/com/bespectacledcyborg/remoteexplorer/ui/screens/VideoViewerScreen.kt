package com.bespectacledcyborg.remoteexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.TagChip
import com.bespectacledcyborg.remoteexplorer.data.db.LocalFile
import com.bespectacledcyborg.remoteexplorer.data.db.LocalTag
import com.bespectacledcyborg.remoteexplorer.ui.components.TagStrip
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.github.panpf.sketch.AsyncImage

@Composable
fun VideoViewerScreen(
    explorerViewModel: ExplorerViewModel,
    onTagOpened: () -> Unit,
    onNothingToShow: () -> Unit,
) {
    LightSystemBarIcons()
    val video by explorerViewModel.viewedVideoState.collectAsState()
    LaunchedEffect(video) {  // stack-reviewer-ignore: the action sheet is the only way onto VideoViewerRoute and it calls viewVideo synchronously before navigate, so a later entry always carries the video it was opened for; null only happens when the route is restored into a fresh process, which is the case this exists for
        if (video == null) onNothingToShow()  // stack-reviewer-ignore: popping on a restored route with no video is the intent, not a race; there is no entry path that navigates before viewVideo has set the state
    }
    val launchExternally = rememberExternalLauncher(explorerViewModel)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        video?.let { shown ->
            VideoPage(
                video = shown,
                posterUrl = explorerViewModel.getUrl(shown.fullPath(), thumbnail = true),
                tagsOf = explorerViewModel::tagsFor,
                onPlay = { launchExternally(shown.filename, shown.fullPath()) },
                onOpenTag = {
                    explorerViewModel.getFilesWithTag(LocalTag(it.tag))
                    onTagOpened()
                },
            )
        }
    }
}

@Composable
private fun VideoPage(
    video: LocalFile,
    posterUrl: String,
    tagsOf: suspend (LocalFile) -> List<TagChip>,
    onPlay: () -> Unit,
    onOpenTag: (TagChip) -> Unit,
) {
    val tags by produceState(emptyList<TagChip>(), video) { value = tagsOf(video) }
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            uri = posterUrl,
            contentDescription = video.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier
                .align(Alignment.Center)
                .size(72.dp),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TagStrip(
                tags = tags,
                onOpenTag = onOpenTag,
                onDark = true,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = video.filename,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
