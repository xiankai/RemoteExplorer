package com.bespectacledcyborg.remoteexplorer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.data.Caption
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import android.app.Activity
import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bespectacledcyborg.remoteexplorer.data.views.IMAGE_DWELL_MILLIS
import kotlinx.coroutines.delay
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.state.ColorPainterStateImage
import com.github.panpf.sketch.state.ThumbnailMemoryCacheStateImage
import com.github.panpf.zoomimage.SketchZoomAsyncImage

/**
 * Images one at a time, swiped through, with pinch and double-tap zoom.
 *
 * Each page shows the file's thumbnail the moment it opens and swaps in the full image the
 * frame it has loaded (#67). The thumbnail is almost always already made — the grid the
 * user tapped from asked for it — so it is a cache hit on the loopback server, where the
 * original is a whole file off the share. The page used to show nothing at all until then:
 * a light grey rectangle on a phone, for as long as the share took.
 */
@Composable
fun ImageBrowserScreen(
    explorerViewModel: ExplorerViewModel,
) {
    val images by explorerViewModel.browsableImagesState.collectAsState()
    val initialImage by explorerViewModel.initialBrowsableImageState.collectAsState()

    val pagerState = rememberPagerState(initialPage = initialImage, pageCount = { images.size })
    // The viewer is black in either theme, so the system bars' icons go light while it is
    // up — in the light theme they would otherwise be dark icons on black — and go back to
    // whatever the theme set when it closes.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            lightStatus?.let { bars.isAppearanceLightStatusBars = it }
            lightNavigation?.let { bars.isAppearanceLightNavigationBars = it }
        }
    }

    // A tap hides the caption, so a picture can be looked at with nothing over it.
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Black, as every photo viewer is. It was light grey with rounded corners, which
            // framed every picture in a bright border and washed out anything dark.
            .background(Color.Black)
    ) {
        // The page being looked at gets the share to itself first. Its neighbours show
        // their thumbnails straight away but only start on their originals once it has
        // landed, so opening an image is never three downloads racing for one link.
        val settledPage = pagerState.settledPage
        var settledPageLoaded by remember(settledPage) { mutableStateOf(false) }

        // An open is a settled page held past the dwell threshold (#76), so flicking
        // through a folder credits nothing, and the neighbours the pager pre-loads are
        // never counted. Only while the viewer is in front: time with the app in the
        // background is not looking. Once per landing, however often it is resumed.
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(settledPage, images) {
            val image = images.getOrNull(settledPage) ?: return@LaunchedEffect
            var counted = false
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (counted) return@repeatOnLifecycle
                delay(IMAGE_DWELL_MILLIS)
                counted = true
                explorerViewModel.recordOpen(image)
            }
        }

        HorizontalPager(
            state = pagerState,
            // The pages either side are composed, and so loaded, while this one is on
            // screen. That is the prefetch: by the time a swipe lands, the neighbour has
            // been fetching for as long as the user spent looking at this one.
            beyondViewportPageCount = 1,
            key = { images[it].fullPath() },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val image = images[page]
            ImagePage(
                fullUrl = explorerViewModel.getUrl(image.fullPath()),
                // The thumbnail URL the grid used, so it is the same cache entry — ours on
                // the server and Sketch's in memory. Never the original (#27).
                thumbnailUrl = explorerViewModel.getUrl(image.fullPath(), thumbnail = true),
                contentDescription = image.filename,
                loadFull = page == settledPage || settledPageLoaded,
                onFullLoaded = { if (page == settledPage) settledPageLoaded = true },
                onTap = { chromeVisible = !chromeVisible },
            )
        }

        val showCaptions by explorerViewModel.showCaptionsState.collectAsState()
        val shownImage = images.getOrNull(pagerState.currentPage)
        var caption by remember { mutableStateOf<Caption?>(null) }
        LaunchedEffect(shownImage?.connectionId, shownImage?.parentPath) {
            caption = shownImage?.let { explorerViewModel.captionFor(it) }
        }

        AnimatedVisibility(
            visible = chromeVisible && images.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val current = images.getOrNull(pagerState.currentPage)
            val galleryCaption = caption
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    // A scrim rather than bare white text, which vanished over any light
                    // picture.
                    .background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (galleryCaption != null && showCaptions) {
                    CaptionText(galleryCaption)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) {
                        Text(
                            text = current.filename,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (galleryCaption != null) {
                        IconButton(onClick = { explorerViewModel.setShowCaptions(!showCaptions) }) {
                            Icon(
                                if (showCaptions) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                                contentDescription = if (showCaptions) "Hide captions" else "Show captions",
                                tint = Color.White,
                            )
                        }
                    }
                }
                Text(
                    text = "${pagerState.currentPage + 1} of ${images.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CaptionText(caption: Caption) {
    var showOriginal by remember(caption) { mutableStateOf(false) }
    val original = caption.original
    val text = if (showOriginal && original != null) original else caption.text
    val originalLabel = "original" + caption.originalLanguage?.let { " ($it)" }.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState())
            .clickable(enabled = original != null) { showOriginal = !showOriginal }
            .padding(bottom = 8.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (original != null) {
            Text(
                text = if (showOriginal) "${originalLabel.replaceFirstChar { it.uppercase() }} · tap for English" else "English · tap for $originalLabel",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ImagePage(
    fullUrl: String,
    thumbnailUrl: String,
    contentDescription: String,
    loadFull: Boolean,
    onFullLoaded: () -> Unit,
    onTap: () -> Unit,
) {
    if (!loadFull) {
        AsyncImage(
            uri = thumbnailUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Sketch decodes this at the size of the screen, not of the file, so a 50 MP original
    // costs a screen's worth of memory. Zooming in then reads the file again tile by tile at
    // full resolution (subsampling), which is what keeps it sharp.
    // How long the original took to replace the thumbnail, and where it came from. The
    // viewer's cost is otherwise invisible: nothing else says whether a swipe was served
    // from memory, the disk cache or the share.
    val state = rememberAsyncImageState()
    val composedAt = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(state.result) {
        val result = state.result
        if (result is ImageResult.Success) {
            onFullLoaded()
            Log.d(
                "ImageBrowser",
                "Full image $contentDescription in ${SystemClock.elapsedRealtime() - composedAt}ms " +
                    "from ${result.dataFrom}",
            )
        }
    }

    SketchZoomAsyncImage(
        state = state,
        request = ComposableImageRequest(fullUrl) {
            // Until the original is in, the thumbnail the grid already holds in memory,
            // drawn full-screen at the same scale and place. It is swapped for the original
            // in the frame that arrives — no crossfade, and no blank frame between.
            placeholder(
                ThumbnailMemoryCacheStateImage(
                    uri = thumbnailUrl,
                    defaultImage = ColorPainterStateImage(Color.Black),
                )
            )
        },
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        onTap = { onTap() },
    )
}
