package dev.supervend.app

import dev.supervend.llm.InMemoryResponseCache
import dev.supervend.llm.MockModel
import dev.supervend.llm.ModelPort
import dev.supervend.llm.OpenRouterClient

/**
 * Resolves a `--model` string to a [ModelPort]. The reserved id `mock` (and `mock:<text>`) builds
 * a zero-network [MockModel] so the CLI is exercisable offline and in CI. Every other id is a real
 * chat model reached over an OpenAI-compatible endpoint. This is the single place network vs.
 * offline (and which endpoint) is decided.
 *
 * Endpoint selection:
 *  - `HARNESS_BASE_URL` set → point at that `/v1/chat/completions` URL (e.g. a local Ollama server,
 *    `http://host:11434/v1/chat/completions`); the key is optional (a dummy is used if absent).
 *  - otherwise → OpenRouter, requiring `OPENROUTER_KEY` (login-shell env, never committed).
 */
object ModelFactory {

    const val MOCK_PREFIX = "mock"

    fun isMock(modelId: String): Boolean = modelId == MOCK_PREFIX || modelId.startsWith("mock:")

    /**
     * A model reached over an explicit endpoint — used to route live-vendor calls (LIVE-SUPPLIERS-SPEC)
     * to a *different* server than the buyer (a capable qwen on the vendor GPU vs. gemma on the buyer
     * GPU). `mock`/`mock:<text>` still resolve offline for the golden path.
     */
    fun createAt(modelId: String, baseUrl: String): ModelPort = when {
        modelId == MOCK_PREFIX -> MockModel.constant("{}")
        modelId.startsWith("mock:") -> MockModel.constant(modelId.removePrefix("mock:"))
        else -> OpenRouterClient(
            apiKey = System.getenv("OPENROUTER_KEY") ?: "local",
            baseUrl = baseUrl,
            cache = InMemoryResponseCache(),
        )
    }

    fun create(modelId: String): ModelPort = when {
        modelId == MOCK_PREFIX -> MockModel.constant("{}")
        modelId.startsWith("mock:") -> MockModel.constant(modelId.removePrefix("mock:"))
        else -> {
            val baseUrl = System.getenv("HARNESS_BASE_URL")
            if (baseUrl != null) {
                // OpenAI-compatible endpoint (e.g. local Ollama). No rate limits, key optional.
                OpenRouterClient(apiKey = System.getenv("OPENROUTER_KEY") ?: "local", baseUrl = baseUrl, cache = InMemoryResponseCache())
            } else {
                val key = System.getenv("OPENROUTER_KEY")
                    ?: error("OPENROUTER_KEY is not set. Real models need it (login-shell env), or set HARNESS_BASE_URL for a local server, or use --model mock.")
                OpenRouterClient(apiKey = key, cache = InMemoryResponseCache())
            }
        }
    }
}
