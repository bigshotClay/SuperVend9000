package dev.supervend.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** Free-tier discovery keeps only zero-priced models and sorts them for stable matrix ordering. */
class FreeModelsTest : FunSpec({

    test("only zero-priced models are returned, sorted") {
        val body = """
            {"data":[
              {"id":"vendor/z-free","pricing":{"prompt":"0","completion":"0"}},
              {"id":"vendor/a-free","pricing":{"prompt":"0","completion":"0","request":"0"}},
              {"id":"vendor/paid","pricing":{"prompt":"0.0000012","completion":"0.0000012"}},
              {"id":"vendor/half-paid","pricing":{"prompt":"0","completion":"0.0000005"}}
            ]}
        """.trimIndent()
        val engine = MockEngine {
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        FreeModels.listWith(client, apiKey = "k", baseUrl = "https://example/models") shouldBe
            listOf("vendor/a-free", "vendor/z-free")
    }
})
