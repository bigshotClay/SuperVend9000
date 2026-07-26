package dev.supervend.llm.qualify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The answer shapes the qualification battery asks a model to emit. These are `@Serializable`,
 * so their agent-facing JSON schemas are generated (CLAUDE.md #7) via `JsonSchema.of<T>()` and
 * shown to the model — never hand-written. They mirror the *shapes* of real seat outputs
 * (classification, extraction, a structured quote) without being any specific persona's schema.
 */

@Serializable
data class ClassificationAnswer(val classification: String)

@Serializable
data class ExtractionAnswer(val value: String)

@Serializable
data class QuoteAnswer(val unitCents: Long, val units: Int)

@Serializable
data class WordAnswer(val word: String)

/**
 * Lenient JSON extraction: weak models wrap JSON in prose or fences. We pull the first balanced
 * top-level `{...}` object out of the text before parsing. Pure and total — returns null on
 * failure rather than throwing, so a check never crashes on garbage.
 */
object JsonExtract {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun firstObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) {
                        val slice = text.substring(start, i + 1)
                        return runCatching { lenient.parseToJsonElement(slice) as? JsonObject }.getOrNull()
                    }
                }
            }
        }
        return null
    }
}
