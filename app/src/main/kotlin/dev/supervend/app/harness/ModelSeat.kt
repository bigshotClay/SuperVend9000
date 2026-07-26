package dev.supervend.app.harness

import dev.supervend.llm.CompletionRequest
import dev.supervend.llm.CompletionResult
import dev.supervend.llm.ContextPackage
import dev.supervend.llm.ModelPort
import dev.supervend.llm.PromptBuilder
import dev.supervend.llm.qualify.JsonExtract
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The single path a model-staffed seat takes (personas §5): assemble the fixed-order context package
 * (state brief → inputs → creed → output schema, CLAUDE.md #8), call the model, extract the first
 * JSON object, and deserialize it into the seat's typed artifact. Parse failures and unusable output
 * both surface as `null` so every caller can apply the harness's defining move — fall back to a safe
 * default — rather than trust the model. The model is never load-bearing; it is an *optional*
 * improvement the gates and fallbacks contain.
 */
class ModelSeat(private val model: ModelPort, private val modelId: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    data class Result<T>(val value: T?, val completion: CompletionResult) {
        val usable: Boolean get() = value != null
    }

    /**
     * Run a seat. [schema] is generated from the artifact type by the caller (never hand-written,
     * CLAUDE.md #7); [serializer] deserializes the model's JSON into [T].
     */
    suspend fun <T> call(
        creed: String,
        stateBrief: String,
        inputs: String,
        schema: String,
        serializer: KSerializer<T>,
    ): Result<T> {
        val pkg = ContextPackage(stateBrief = stateBrief, inputs = inputs, creed = creed, outputSchema = schema)
        val completion = model.complete(CompletionRequest(modelId, PromptBuilder.initial(pkg)))
        val value = when (completion) {
            is CompletionResult.Completed -> parse(completion.text, serializer)
            is CompletionResult.Unusable -> null
        }
        return Result(value, completion)
    }

    private fun <T> parse(text: String, serializer: KSerializer<T>): T? {
        val obj = JsonExtract.firstObject(text) ?: return null
        return runCatching { json.decodeFromJsonElement(serializer, obj) }.getOrNull()
    }
}
