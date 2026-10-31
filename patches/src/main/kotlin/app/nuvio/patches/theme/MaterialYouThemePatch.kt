package app.nuvio.patches.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patcher.util.registersUsed
import app.nuvio.patches.shared.Constants.COMPATIBILITY_NUVIO
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.VariableRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS = "Lapp/nuvio/extension/MaterialYouTheme;"
private const val ENUM_SUPERCLASS = "Ljava/lang/Enum;"
private const val NEW_THEME_NAME = "MATERIAL"

/**
 * Names of the theme enum constants. String constants are never renamed by R8, so the (obfuscated)
 * enum class is the one enum that contains all of these.
 */
private val THEME_ENUM_ANCHORS = listOf("CRIMSON", "OCEAN", "VIOLET", "EMERALD", "AMBER", "ROSE", "WHITE", "CUSTOM")

/**
 * Adds the "Material" (Material You) theme, like the upstream commit "Add Material You theme".
 *
 * 1. A new enum constant MATERIAL is appended to the theme enum, so it shows in the theme picker and
 *    can be saved and restored by name.
 * 2. The palette function (theme + custom colors -> palette) returns a palette built from the system
 *    Material You colors when the theme is MATERIAL.
 * 3. The theme picker chip switches over the theme to pick its label. MATERIAL reuses the CUSTOM
 *    branch there (so it cannot hit the "unknown theme" error) and its label text is then replaced
 *    with "Material".
 *
 * Everything is found through strings and structure, never through fixed obfuscated names.
 */
@Suppress("unused")
val materialYouThemePatch = bytecodePatch(
    name = "Material You theme",
    description = "Adds a \"Material\" theme that follows your Android Material You (wallpaper) colors.",
    default = true
) {
    compatibleWith(COMPATIBILITY_NUVIO)

    extendWith("extensions/nuvio.mpe")

    execute {
        // 1. Find the (obfuscated) theme enum.
        val enumTypes = THEME_ENUM_ANCHORS
            .map { anchor ->
                classDefByStrings(anchor)
                    .filter { it.superclass == ENUM_SUPERCLASS }
                    .map { it.type }
                    .toSet()
            }
            .reduce { acc, types -> acc intersect types }
        if (enumTypes.size != 1) {
            throw PatchException(
                "Expected exactly 1 theme enum containing $THEME_ENUM_ANCHORS but found ${enumTypes.size}. " +
                    "The theme enum changed in this Nuvio version."
            )
        }
        val enumType = enumTypes.single()

        // 2. Append MATERIAL to the enum's values array.
        val clinit = mutableClassDefBy(enumType).methods.firstOrNull { it.name == "<clinit>" }
            ?: throw PatchException("Theme enum $enumType has no static initializer.")
        val clinitImpl = clinit.implementation
            ?: throw PatchException("Theme enum $enumType static initializer has no code.")

        var newInstanceCount = 0
        var arrayIndex = -1
        for (i in 0 until clinitImpl.instructions.size) {
            val instruction = clinitImpl.instructions[i]
            if (instruction.opcode == Opcode.NEW_INSTANCE &&
                instruction.getReference<TypeReference>()?.type == enumType
            ) {
                newInstanceCount++
            }
            if (arrayIndex < 0 &&
                (instruction.opcode == Opcode.FILLED_NEW_ARRAY || instruction.opcode == Opcode.FILLED_NEW_ARRAY_RANGE) &&
                instruction.getReference<TypeReference>()?.type == "[$enumType"
            ) {
                arrayIndex = i
            }
        }
        if (arrayIndex < 0) {
            throw PatchException("Could not find where the values array of $enumType is created.")
        }

        val constantCount = (clinitImpl.instructions[arrayIndex] as VariableRegisterInstruction).registerCount
        if (constantCount != newInstanceCount) {
            throw PatchException(
                "Theme enum has $newInstanceCount constants but its values array has $constantCount entries."
            )
        }

        val resultInstruction = clinitImpl.instructions[arrayIndex + 1]
        if (resultInstruction.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException("Unexpected code after the values array of $enumType.")
        }
        val arrayRegister = (resultInstruction as OneRegisterInstruction).registerA

        // After the array is built, all registers except the array's are free to use.
        val temps = (0..15).filter { it != arrayRegister && it < clinitImpl.registerCount }.take(5)
        if (temps.size < 5) {
            throw PatchException("Not enough registers in the static initializer of $enumType.")
        }
        val (t1, t2, t3, t4, t5) = temps
        val oldSize = "0x${constantCount.toString(16)}"
        val newSize = "0x${(constantCount + 1).toString(16)}"

        clinit.addInstructions(
            arrayIndex + 2,
            """
                new-instance v$t1, $enumType
                const-string v$t2, "$NEW_THEME_NAME"
                const/16 v$t3, $oldSize
                invoke-direct { v$t1, v$t2, v$t3 }, Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V
                const/16 v$t2, $newSize
                new-array v$t2, v$t2, [$enumType
                const/4 v$t3, 0x0
                const/4 v$t4, 0x0
                const/16 v$t5, $oldSize
                invoke-static { v$arrayRegister, v$t3, v$t2, v$t4, v$t5 }, Ljava/lang/System;->arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V
                const/16 v$t3, $oldSize
                aput-object v$t1, v$t2, v$t3
                move-object v$arrayRegister, v$t2
            """
        )

        // 3. Patch the two places that switch over the theme (they call ordinal() and then switch).
        //    - returns an object: the palette function.
        //    - returns void: the theme picker chip.
        var paletteHooks = 0
        var chipHooks = 0

        Fingerprint(
            filters = listOf(
                methodCall(definingClass = "Ljava/lang/Enum;", name = "ordinal")
            ),
            custom = { method, _ -> method.parameterTypes.firstOrNull()?.toString() == enumType }
        ).matchAllMethodIndicesForEach { index ->
            val impl = implementation ?: return@matchAllMethodIndicesForEach
            val switchIndex = index + 2
            if (switchIndex >= impl.instructions.size) return@matchAllMethodIndicesForEach
            val switchOpcode = impl.instructions[switchIndex].opcode
            if (switchOpcode != Opcode.PACKED_SWITCH && switchOpcode != Opcode.SPARSE_SWITCH) {
                return@matchAllMethodIndicesForEach
            }
            if (impl.instructions[index + 1].opcode != Opcode.MOVE_RESULT) {
                return@matchAllMethodIndicesForEach
            }

            val receiverRegister = getInstruction(index).registersUsed[0]
            val ordinalRegister = (impl.instructions[index + 1] as OneRegisterInstruction).registerA

            if (returnType != "V") {
                // Palette function: return the Material You palette for MATERIAL, before the switch.
                val templateField = classDefBy(definingClass).staticFields
                    .firstOrNull { it.type == returnType }
                    ?: throw PatchException("No existing palette object found in $definingClass.")
                val materialOrdinal = "0x${constantCount.toString(16)}"

                addInstructionsWithLabels(
                    index + 2,
                    """
                        add-int/lit8 v$ordinalRegister, v$ordinalRegister, -$materialOrdinal
                        if-nez v$ordinalRegister, :not_material
                        sget-object v$ordinalRegister, $definingClass->${templateField.name}:$returnType
                        invoke-static/range { v$ordinalRegister .. v$ordinalRegister }, $EXTENSION_CLASS->materialPalette(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$ordinalRegister
                        check-cast v$ordinalRegister, $returnType
                        return-object v$ordinalRegister
                        :not_material
                        add-int/lit8 v$ordinalRegister, v$ordinalRegister, $materialOrdinal
                    """
                )
                paletteHooks++
            } else {
                // Picker chip: MATERIAL takes the CUSTOM branch so the switch does not fail ...
                replaceInstruction(
                    index,
                    "invoke-static/range { v$receiverRegister .. v$receiverRegister }, " +
                        "$EXTENSION_CLASS->switchOrdinal(Ljava/lang/Enum;)I"
                )

                // ... and the label string produced after the switch is replaced with "Material".
                // The label is the result of the first static call after the switch that returns a
                // String and takes at least 2 arguments (the string resource lookup). Its exact
                // signature changes between releases (2 args in 0.6.5, 3 in 0.6.6), so only the
                // return type and a minimum argument count are matched. If that is not found, fall
                // back to the first call of any kind after the switch that returns a String.
                fun findStringCall(strict: Boolean): Int {
                    for (i in switchIndex + 1 until impl.instructions.size) {
                        val instruction = impl.instructions[i]
                        val isStatic = instruction.opcode == Opcode.INVOKE_STATIC ||
                            instruction.opcode == Opcode.INVOKE_STATIC_RANGE
                        val isCall = isStatic ||
                            instruction.opcode == Opcode.INVOKE_VIRTUAL ||
                            instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE ||
                            instruction.opcode == Opcode.INVOKE_INTERFACE ||
                            instruction.opcode == Opcode.INVOKE_INTERFACE_RANGE
                        if (!isCall || (strict && !isStatic)) continue
                        val reference = instruction.getReference<MethodReference>() ?: continue
                        if (reference.returnType != "Ljava/lang/String;") continue
                        if (strict && reference.parameterTypes.size < 2) continue
                        if (i + 1 < impl.instructions.size &&
                            impl.instructions[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT
                        ) return i
                    }
                    return -1
                }
                val stringCallIndex = findStringCall(strict = true).takeIf { it >= 0 } ?: findStringCall(strict = false)
                if (stringCallIndex < 0) {
                    throw PatchException("Could not find the theme label lookup in $definingClass->$name.")
                }
                val labelRegister = (impl.instructions[stringCallIndex + 1] as OneRegisterInstruction).registerA
                if (receiverRegister > 15 || labelRegister > 15) {
                    throw PatchException("Theme or label register out of range in $definingClass->$name.")
                }

                addInstructions(
                    stringCallIndex + 2,
                    """
                        invoke-static { v$receiverRegister, v$labelRegister }, $EXTENSION_CLASS->materialLabel(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$labelRegister
                    """
                )
                chipHooks++
            }
        }

        if (paletteHooks != 1 || chipHooks != 1) {
            throw PatchException(
                "Expected to patch 1 palette function and 1 theme chip but patched $paletteHooks and $chipHooks. " +
                    "Nuvio changed how themes are switched in this version."
            )
        }
    }
}
