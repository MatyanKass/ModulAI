package com.modulai.app.image

import android.content.Context
import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.Tool
import com.modulai.core.runtime.ToolSpec
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

/**
 * Lets the chat model make a picture with the image model chosen on the image screen,
 * at the medium preset. The result names the saved PNG on its first line; [imageFile]
 * reads it back so the chat can show the picture.
 */
class GenerateImageTool(context: Context) : Tool {
    private val controller = ImageController.get(context)

    override val spec = ToolSpec(
        NAME,
        "Create a picture from a text description and show it to the user. Takes a minute or more. " +
            "Write the prompt in English as a comma-separated description of the picture: subject, " +
            "setting, style, lighting.",
        """{"type":"object","properties":{""" +
            """"prompt":{"type":"string","description":"What the picture shows, in English"},""" +
            """"negative_prompt":{"type":"string","description":"What the picture must not contain (optional)"}""" +
            """},"required":["prompt"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) return "Error: empty prompt"
        val negative = args.optString("negative_prompt").trim().ifEmpty { null }
        log.add(LogKind.NOTE, "generate_image", prompt)
        return try {
            val record = controller.generateForTool(prompt, negative)
            log.add(LogKind.TOOL_RESULT, record.file.name, record.file.absolutePath)
            "$PATH_PREFIX${record.file.absolutePath}\n" +
                "The picture (${record.width}x${record.height}, seed ${record.seed}) is ready and is being " +
                "shown to the user. Do not describe it in detail: you have not seen it."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add(LogKind.ERROR, "generate_image", e.message ?: e.toString())
            "Error: ${e.message ?: e}"
        }
    }

    companion object {
        const val NAME = "generate_image"
        private const val PATH_PREFIX = "Image saved to: "

        /** Whether an image model is chosen and present, i.e. the tool is worth offering. */
        fun isAvailable(context: Context) = ImageController.get(context).canGenerate()

        /** The PNG a successful [run] result refers to, or null for an error result. */
        fun imageFile(toolResult: String): File? =
            toolResult.lineSequence().firstOrNull()
                ?.takeIf { it.startsWith(PATH_PREFIX) }
                ?.let { File(it.removePrefix(PATH_PREFIX)) }
                ?.takeIf { it.isFile }
    }
}
