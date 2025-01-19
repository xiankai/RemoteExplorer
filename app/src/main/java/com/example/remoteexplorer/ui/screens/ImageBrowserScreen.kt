package com.example.remoteexplorer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.remoteexplorer.R
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import java.nio.file.Paths

@Composable
fun ImageBrowserScreen(
    explorerViewModel: ExplorerViewModel,
) {
    val images by explorerViewModel.browsableImagesState.collectAsState()
    val initialImage by explorerViewModel.initialBrowsableImageState.collectAsState()

    val pagerState = rememberPagerState(initialPage = initialImage, pageCount = { images.size })
    Box {
        HorizontalPager(state = pagerState) { page ->
            val image = images[page]
            val path = Paths.get(image.parentPath, image.filename)
            val url = explorerViewModel.getUrl(path.toString())
            val contentDescription = image.filename
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.LightGray, shape = RoundedCornerShape(2.dp)),
                placeholder = painterResource(R.drawable.image_24px),
                error = painterResource(R.drawable.sentiment_very_dissatisfied_24px)
            )
        }

        Text(
            text = "Image ${pagerState.currentPage + 1} of ${images.size}",
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            color = Color.White,
        )
    }
}