package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun rememberToday(zone: ZoneId): LocalDate {
    var today by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            today = LocalDate.now(zone)
            delay(untilNextMidnight(zone).toMillis().coerceAtLeast(1))
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, zone) {
        val rereadOnResume = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) today = LocalDate.now(zone)
        }
        lifecycleOwner.lifecycle.addObserver(rereadOnResume)
        onDispose { lifecycleOwner.lifecycle.removeObserver(rereadOnResume) }
    }
    return today
}

private fun untilNextMidnight(zone: ZoneId): Duration {
    val now = ZonedDateTime.now(zone)
    return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone))
}
