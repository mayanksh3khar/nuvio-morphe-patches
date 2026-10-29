package app.nuvio.patches.player

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patcher.util.registersUsed
import app.nuvio.patches.shared.Constants.COMPATIBILITY_NUVIO
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/nuvio/extension/P2pExternalPlayer;"

/**
 * Text the (obfuscated) ExternalPlayerPlaybackRequest data class prints in its toString().
 * String constants are never renamed by R8, so this finds the class in any app version.
 */
private const val REQUEST_TOSTRING_ANCHOR = "ExternalPlayerPlaybackRequest(sourceUrl="

/**
 * Why this is needed: for P2P (torrent) streams Nuvio uses a placeholder source URL
 * `torrent://<infoHash>?index=N` while the internal player resolves the real playable URL
 * from its local torrent engine. The "open in external player" action sends the
 * placeholder to the external player, which cannot play it.
 *
 * What this does: every place that builds an ExternalPlayerPlaybackRequest passes its source URL
 * through [EXTENSION_CLASS].resolveSourceUrl() first. The extension replaces a `torrent://`
 * placeholder with the local stream URL of the running P2P engine, and leaves every other URL alone.
 */
@Suppress("unused")
val p2pExternalPlayerPatch = bytecodePatch(
    name = "P2P external player fix",
    description = "Lets P2P (torrent) streams open in an external player by sending it the " +
        "resolved local stream URL instead of the torrent:// placeholder.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NUVIO)

    extendWith("extensions/nuvio.mpe")

    execute {
        // 1. Find the (obfuscated) request class by its toString() text.
        val requestClasses = classDefByStrings(REQUEST_TOSTRING_ANCHOR)
        if (requestClasses.size != 1) {
            throw PatchException(
                "Expected exactly 1 class containing \"$REQUEST_TOSTRING_ANCHOR\" but found " +
                    "${requestClasses.size}. The ExternalPlayerPlaybackRequest class changed in this Nuvio version."
            )
        }
        val requestType = requestClasses.first().type

        // 2. Rewrite the source URL argument at every call to its constructor, outside the class itself.
        var patchedCalls = 0

        Fingerprint(
            filters = listOf(
                methodCall(definingClass = requestType, name = "<init>")
            ),
            custom = { _, classDef -> classDef.type != requestType }
        ).matchAllMethodIndicesForEach { index ->
            val instruction = getInstruction(index)

            // The source URL is the first constructor parameter, a String.
            val reference = instruction.getReference<MethodReference>()
                ?: return@matchAllMethodIndicesForEach
            if (reference.parameterTypes.firstOrNull()?.toString() != "Ljava/lang/String;") {
                return@matchAllMethodIndicesForEach
            }

            // registersUsed handles both the normal and the /range form of the invoke.
            // Index 0 is the object being constructed, index 1 is the source URL.
            val sourceRegister = instruction.registersUsed.getOrNull(1)
                ?: return@matchAllMethodIndicesForEach

            addInstructions(
                index,
                """
                    invoke-static/range { v$sourceRegister .. v$sourceRegister }, $EXTENSION_CLASS->resolveSourceUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$sourceRegister
                """
            )
            patchedCalls++
        }

        if (patchedCalls == 0) {
            throw PatchException(
                "Found the request class ($requestType) but no place that creates it. " +
                    "Nuvio changed how external player requests are built."
            )
        }
    }
}
