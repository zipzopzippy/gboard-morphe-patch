package app.gboardnet.patches.scantext

import app.gboardnet.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByStrings
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

private const val FLAG_NAME = "enable_ocr"

/**
 * Gboard declares its feature flags as  flag("name", default).  Scan text is behind "enable_ocr",
 * which defaults to false and is normally switched on remotely. Flip the default to true.
 * Matched by the flag name only, so it does not depend on obfuscated class names.
 */
@Suppress("unused")
val enableScanTextPatch = patch(
    name = "Enable Scan text",
    description = "Shows Gboard's Scan text tool (take a photo of words to type them). " +
        "It is off by default and normally enabled remotely by Google, which the network guard prevents.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        var patched = 0
        classDefByStrings(FLAG_NAME).forEach { classDef ->
            val mutableClass = mutableClassDefBy(classDef)
            mutableClass.methods.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, instruction ->
                    if (instruction.opcode != Opcode.CONST_STRING) return@forEachIndexed
                    val string = ((instruction as ReferenceInstruction).reference as StringReference).string
                    if (string != FLAG_NAME) return@forEachIndexed

                    // Expect: const/4 vX, 0x0  then  invoke-static {.., vX}, ->(Ljava/lang/String;Z)
                    val literal = instructions.getOrNull(index + 1) ?: return@forEachIndexed
                    val call = instructions.getOrNull(index + 2) ?: return@forEachIndexed
                    if (literal.opcode != Opcode.CONST_4 ||
                        (literal as NarrowLiteralInstruction).narrowLiteral != 0
                    ) return@forEachIndexed
                    if (call.opcode != Opcode.INVOKE_STATIC) return@forEachIndexed
                    val ref = (call as ReferenceInstruction).reference as MethodReference
                    if (ref.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;", "Z")) {
                        return@forEachIndexed
                    }

                    val register = (literal as OneRegisterInstruction).registerA
                    method.replaceInstruction(index + 1, "const/4 v$register, 0x1")
                    patched++
                }
            }
        }

        if (patched == 0) {
            throw PatchException("Could not find the '$FLAG_NAME' flag declaration in this Gboard version.")
        }
        Logger.getLogger("EnableScanTextPatch").info("Scan text: enabled $patched flag declaration(s)")
    }
}
