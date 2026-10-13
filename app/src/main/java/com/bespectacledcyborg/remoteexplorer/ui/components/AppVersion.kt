package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.BuildConfig

fun appVersionLabel(
    versionName: String = BuildConfig.VERSION_NAME,
    commitSha: String = BuildConfig.COMMIT_SHA,
    buildType: String = BuildConfig.BUILD_TYPE,
): String = listOfNotNull(
    versionName,
    commitSha,
    buildType.takeUnless { it == "release" },
).joinToString(" · ")
