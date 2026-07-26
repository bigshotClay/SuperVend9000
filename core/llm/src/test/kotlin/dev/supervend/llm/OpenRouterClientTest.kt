package dev.supervend.llm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

private fun okBody(text: String) =
    """{"choices":[{"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],""" +
        """"usage":{"prompt_tokens":3,"completion_tokens":5}}"""

private fun mockClient(engine: MockEngine) = HttpClient(engine) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

class OpenRouterClientTest : FunSpec({

    test("a 200 with content yields Completed and no sleeps") {
        val engine = MockEngine { respond(okBody("hello"), HttpStatusCode.OK, jsonHeaders) }
        val client = openRouterForTest(apiKey = "k", httpClient = mockClient(engine), sleep = { error("should not sleep") })
        val r = client.complete(CompletionRequest("m", "hi"))
        r.shouldBeInstanceOf<CompletionResult.Completed>()
        r.text shouldBe "hello"
    }

    test("429 is retried with backoff, then succeeds") {
        val calls = AtomicInteger(0)
        val sleeps = mutableListOf<Long>()
        val engine = MockEngine {
            if (calls.getAndIncrement() < 2) respond("busy", HttpStatusCode.TooManyRequests, jsonHeaders)
            else respond(okBody("done"), HttpStatusCode.OK, jsonHeaders)
        }
        val client = openRouterForTest(apiKey = "k", httpClient = mockClient(engine), sleep = { sleeps += it })
        val r = client.complete(CompletionRequest("m", "hi"))
        r.shouldBeInstanceOf<CompletionResult.Completed>()
        r.text shouldBe "done"
        calls.get() shouldBe 3
        sleeps shouldBe listOf(250L, 500L) // exponential
    }

    test("persistent 5xx on primary falls back once, records the event, then succeeds") {
        val fallbacks = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            // Fail every call to the primary; succeed for the fallback model.
            val bodyText = (request.body as io.ktor.http.content.TextContent).text
            if (bodyText.contains(OpenRouterClient.FALLBACK_MODEL)) respond(okBody("saved"), HttpStatusCode.OK, jsonHeaders)
            else respond("boom", HttpStatusCode.InternalServerError, jsonHeaders)
        }
        val client = openRouterForTest(
            apiKey = "k",
            httpClient = mockClient(engine),
            maxRetries = 1,
            sleep = { },
            onFallback = { from, to -> fallbacks += from to to },
        )
        val r = client.complete(CompletionRequest("weak-model", "hi"))
        r.shouldBeInstanceOf<CompletionResult.Completed>()
        r.text shouldBe "saved"
        fallbacks shouldBe listOf("weak-model" to OpenRouterClient.FALLBACK_MODEL)
    }

    test("cacheable request is served from cache on the second call") {
        val calls = AtomicInteger(0)
        val engine = MockEngine { calls.incrementAndGet(); respond(okBody("cached"), HttpStatusCode.OK, jsonHeaders) }
        val client = openRouterForTest(apiKey = "k", cache = InMemoryResponseCache(), httpClient = mockClient(engine), sleep = { })
        val req = CompletionRequest("m", "research the price of cola", cacheable = true)
        client.complete(req)
        client.complete(req)
        calls.get() shouldBe 1
    }
})
