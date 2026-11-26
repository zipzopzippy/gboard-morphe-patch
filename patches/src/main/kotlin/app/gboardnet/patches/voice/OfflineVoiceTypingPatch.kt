package app.gboardnet.patches.voice

import app.gboardnet.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.classDefByStrings
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

private const val GUARD_CLASS = "Lapp/gboardnet/extension/NetworkGuard;"

/** Logged by FallbackOnDeviceRecognitionProvider.canHandle() when the language is not en-US. */
private const val FALLBACK_LANGUAGE_LOG =
    "canHandle() : Only en-us is supported for fallback on-device recognition."

/** Logged by SpeechRecognitionFactory.getSpeechRecognizer() right before it creates the S3 recognizer. */
private const val ONLINE_FIRST_LOG =
    "[RD] Prioritizing the new S3 recognizer over the on-device recognizer. [news3]"

@Suppress("unused")
val offlineVoiceTypingPatch = patch(
    name = "Offline voice typing",
    description = "Lets Gboard's on-device speech recognizer work for all languages it has speech packs for " +
        "(not just English (US)), and prefers it over Google's online recognizer. " +
        "Can be switched in Gboard settings > Network access.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    extendWith("extensions/extension.mpe")

    val preferOffline by booleanOption(
        key = "preferOfflineVoice",
        default = true,
        title = "Prefer offline voice typing",
        description = "Skip Google's online speech recognizer by default (changeable later in Gboard settings).",
    )

    execute {
        val logger = Logger.getLogger("OfflineVoiceTypingPatch")

        mutableClassDefBy(GUARD_CLASS).methods.first { it.name == "patchDefaultPreferOfflineVoice" }
            .addInstructions(
                0,
                """
                    const/4 v0, ${if (preferOffline != false) 1 else 0}
                    return v0
                """,
            )

        // 1. Fallback recognizer: treat every language as supported. Gboard then looks for the
        //    speech pack of the current language (packs exist for en-AU, en-GB, de-DE, ...).
        val fallback = methodsUsingString(FALLBACK_LANGUAGE_LOG).singleOrNull()
            ?: throw PatchException("Could not find the fallback on-device language check")
        var forced = 0
        fallback.instructionsList().withIndex().reversed().forEach { (index, instruction) ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@forEach
            val ref = (instruction as ReferenceInstruction).reference as MethodReference
            if (ref.name != "equals" || ref.returnType != "Z" || ref.definingClass.startsWith("Ljava/")) return@forEach
            val next = fallback.instructionsList().getOrNull(index + 1) ?: return@forEach
            if (next.opcode != Opcode.MOVE_RESULT) return@forEach
            val register = (next as OneRegisterInstruction).registerA
            fallback.addInstructions(index + 2, if (register < 16) "const/4 v$register, 0x1" else "const/16 v$register, 0x1")
            forced++
        }
        if (forced == 0) throw PatchException("Unexpected shape of the fallback on-device language check")
        logger.info("Offline voice typing: removed en-US only restriction ($forced checks)")

        // 2. Recognizer selection: pass the online recognizer through the guard, which returns null
        //    when offline is preferred, so Gboard continues with its on-device recognizers.
        val factory = methodsUsingString(ONLINE_FIRST_LOG).singleOrNull()
            ?: throw PatchException("Could not find Gboard's speech recognizer selection")
        val instructions = factory.instructionsList()
        val logIndex = instructions.indexOfFirst { it.isConstString(ONLINE_FIRST_LOG) }
        val callIndex = (logIndex + 1 until instructions.size).firstOrNull { i ->
            val ins = instructions[i]
            ins.opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE,
                Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE_RANGE) &&
                ((ins as ReferenceInstruction).reference as MethodReference).returnType.startsWith("L") &&
                instructions.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
        } ?: throw PatchException("Could not find the online recognizer call")
        val returnType = ((instructions[callIndex] as ReferenceInstruction).reference as MethodReference).returnType
        val register = (instructions[callIndex + 1] as OneRegisterInstruction).registerA
        factory.addInstructions(
            callIndex + 2,
            """
                invoke-static/range { v$register .. v$register }, $GUARD_CLASS->filterOnlineRecognizer(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$register
                check-cast v$register, $returnType
            """,
        )
        logger.info("Offline voice typing: hooked online recognizer selection")
    }
}

private fun Instruction.isConstString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

private fun MutableMethod.instructionsList(): List<Instruction> = implementation!!.instructions.toList()

/** Mutable methods that contain a const-string with exactly [value]. */
private fun methodsUsingString(value: String): List<MutableMethod> =
    classDefByStrings(value).flatMap { classDef ->
        val mutableClass = mutableClassDefBy(classDef)
        mutableClass.methods.filter { method ->
            method.implementation?.instructions?.any { it.isConstString(value) } == true
        }
    }
