package dev.supervend.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * The real OpenRouter model port (software spec §11). The only network surface in the system.
 *
 * Reliability, per spec:
 *  - **Backoff:** exponential retry on 429 / 5xx up to [maxRetries].
 *  - **Fallback:** on repeated failure or an endpoint that has disappeared, switch to the
 *    fallback model once and record a manifest event via [onFallback].
 *  - **Caching:** cacheable requests are served/stored by (modelId, sha256(prompt)).
 *
 * CI never constructs this — MockModel covers the deterministic 90%. Only the nightly E2E and
 * live `qualify`/`baseline`/`run` invocations touch it.
 */
class OpenRouterClient internal constructor(
    private val apiKey: String,
    private val cache: ResponseCache,
    private val maxRetries: Int,
    private val baseUrl: String,
    private val onFallback: (from: String, to: String) -> Unit,
    private val sleep: suspend (Long) -> Unit,
    httpClient: HttpClient?,
) : ModelPort, AutoCloseable {

    /**
     * Public constructor for production callers — no ktor type in the signature, so the HTTP
     * engine stays an implementation detail of `core/llm` (the app never sees it).
     */
    constructor(
        apiKey: String,
        cache: ResponseCache = ResponseCache.Disabled,
        maxRetries: Int = 4,
        baseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
        onFallback: (from: String, to: String) -> Unit = { _, _ -> },
    ) : this(apiKey, cache, maxRetries, baseUrl, onFallback, sleep = { delay(it) }, httpClient = null)

    private val log = LoggerFactory.getLogger(OpenRouterClient::class.java)
    private val ownsClient = httpClient == null
    private val client = httpClient ?: HttpClient {
        install(ContentNegotiation) { json(JSON) }
        // Local reasoning models (and cold model loads) can take well over ktor's ~15s default;
        // the ceiling is generous because fast endpoints return long before it (it is a max, not a wait).
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = (System.getenv("HARNESS_TIMEOUT_MS")?.toLongOrNull()) ?: 300_000
            socketTimeoutMillis = 300_000
            connectTimeoutMillis = 20_000
        }
    }

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        if (request.cacheable) cache.get(request.promptHash)?.let { return it }
        val result = completeWithModel(request, request.modelId)
        return when (result) {
            is CompletionResult.Completed -> {
                if (request.cacheable) cache.put(request.promptHash, result)
                result
            }
            is CompletionResult.Unusable -> result
        }
    }

    /** Attempt the primary model with backoff; on exhaustion, try the manifest-flagged fallback once. */
    private suspend fun completeWithModel(request: CompletionRequest, modelId: String): CompletionResult {
        var attempt = 0
        while (true) {
            val outcome = callOnce(modelId, request.prompt)
            when (outcome) {
                is Outcome.Ok -> return outcome.result
                is Outcome.Retryable -> {
                    if (attempt >= maxRetries) return switchOrFail(request, modelId)
                    val backoffMs = 250L * (1L shl attempt) // 250, 500, 1000, 2000, ...
                    log.warn("OpenRouter {} transient ({}), retry {} in {}ms", modelId, outcome.status, attempt + 1, backoffMs)
                    sleep(backoffMs)
                    attempt++
                }
                is Outcome.Fatal -> return switchOrFail(request, modelId)
            }
        }
    }

    private suspend fun switchOrFail(request: CompletionRequest, currentModel: String): CompletionResult {
        val fallback = FALLBACK_MODEL
        if (currentModel == fallback) return CompletionResult.Unusable(ModelFailure.TRANSPORT_FAILED)
        onFallback(currentModel, fallback)
        log.warn("OpenRouter falling back {} -> {}", currentModel, fallback)
        // One shot on the fallback; if it also fails, surface a transport failure (no infinite loop).
        return when (val outcome = callOnce(fallback, request.prompt)) {
            is Outcome.Ok -> outcome.result
            else -> CompletionResult.Unusable(ModelFailure.TRANSPORT_FAILED)
        }
    }

    private suspend fun callOnce(modelId: String, prompt: String): Outcome {
        val started = System.nanoTime()
        val response: HttpResponse = try {
            client.post(baseUrl) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(model = modelId, messages = listOf(ChatMessage("user", prompt))))
            }
        } catch (e: Exception) {
            log.warn("OpenRouter transport error for {}: {}", modelId, e.message)
            return Outcome.Retryable(status = 0)
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val status = response.status
        if (status == HttpStatusCode.TooManyRequests || status.value in 500..599) {
            return Outcome.Retryable(status.value)
        }
        if (!status.isSuccess()) return Outcome.Fatal(status.value)

        val body: ChatResponse = try {
            response.body()
        } catch (e: Exception) {
            log.warn("OpenRouter unparseable body for {}: {}", modelId, e.message)
            return Outcome.Fatal(status.value)
        }
        val choice = body.choices.firstOrNull()
            ?: return Outcome.Ok(CompletionResult.Unusable(ModelFailure.EMPTY))
        val text = choice.message.content
        if (text.isBlank()) return Outcome.Ok(CompletionResult.Unusable(ModelFailure.EMPTY))
        val reason = when (choice.finishReason) {
            "length" -> ModelFailure.TRUNCATED
            "content_filter" -> ModelFailure.REFUSED
            else -> null
        }
        if (reason != null) return Outcome.Ok(CompletionResult.Unusable(reason))
        val usage = Usage(
            tokensIn = body.usage?.promptTokens ?: 0,
            tokensOut = body.usage?.completionTokens ?: 0,
            latencyMs = latencyMs,
            httpStatus = status.value,
        )
        return Outcome.Ok(CompletionResult.Completed(text, usage))
    }

    override fun close() {
        if (ownsClient) client.close()
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    private sealed interface Outcome {
        data class Ok(val result: CompletionResult) : Outcome
        data class Retryable(val status: Int) : Outcome
        data class Fatal(val status: Int) : Outcome
    }

    companion object {
        const val FALLBACK_MODEL = "openrouter/auto"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Test-only factory that injects a mock ktor engine and controllable backoff — keeps ktor out of production callers. */
internal fun openRouterForTest(
    apiKey: String,
    httpClient: HttpClient,
    cache: ResponseCache = ResponseCache.Disabled,
    maxRetries: Int = 4,
    baseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    onFallback: (from: String, to: String) -> Unit = { _, _ -> },
    sleep: suspend (Long) -> Unit = { },
): OpenRouterClient = OpenRouterClient(apiKey, cache, maxRetries, baseUrl, onFallback, sleep, httpClient)

// ---- OpenRouter chat-completions wire types (subset we use) ----

@Serializable
private data class ChatRequest(val model: String, val messages: List<ChatMessage>)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList(), val usage: ChatUsage? = null)

@Serializable
private data class ChatChoice(
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)
