package app.nuvio.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_NUVIO = Compatibility(
        name = "Nuvio",
        packageName = "com.nuvio.app",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x5B3FD1,
        targets = listOf(
            // Any version, experimental: the patch finds its targets by strings and
            // structure instead of fixed (obfuscated) names, so it may keep working on updates.
            AppTarget(
                version = null,
                isExperimental = true
            ),
            // Version the patch was written against (analysis only, not confirmed on a device).
            AppTarget(
                version = "0.6.5-beta",
                isExperimental = true
            )
        )
    )
}
