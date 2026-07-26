package dev.supervend.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Discovers the OpenRouter free-tier model roster (software spec §11, PRD R43). This is deliberately
 * *uncurated*: the thesis is model-independence, so the strongest evidence comes from running over
 * whatever the free tier happens to offer, not a hand-picked shortlist. The matrix takes this list
 * verbatim.
 *
 * "Free" = both prompt and completion prices are zero. Network-only (the sole network surface stays
 * in `core/llm`); CI never calls this.
 */
object FreeModels {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Fetch every zero-priced model id, sorted for stable matrix ordering. */
    suspend fun list(apiKey: String, baseUrl: String = "https://openrouter.ai/api/v1/models"): List<String> =
        HttpClient { install(ContentNegotiation) { json(JSON) } }.use { listWith(it, apiKey, baseUrl) }

    /** Test seam: run the fetch + free-filter against an injected (mock) client. */
    internal suspend fun listWith(client: HttpClient, apiKey: String, baseUrl: String): List<String> {
        val response: ModelsResponse = client.get(baseUrl) { header("Authorization", "Bearer $apiKey") }.body()
        return response.data.filter { it.isFree() }.map { it.id }.sorted()
    }

    @Serializable
    private data class ModelsResponse(val data: List<ModelEntry> = emptyList())

    @Serializable
    private data class ModelEntry(val id: String, val pricing: Pricing = Pricing()) {
        /** A model is free when neither prompt nor completion tokens are billed. */
        fun isFree(): Boolean = pricing.prompt.zero() && pricing.completion.zero()
        private fun String.zero(): Boolean = toDoubleOrNull() == 0.0
    }

    @Serializable
    private data class Pricing(
        val prompt: String = "0",
        val completion: String = "0",
        @SerialName("request") val request: String = "0",
    )
}
