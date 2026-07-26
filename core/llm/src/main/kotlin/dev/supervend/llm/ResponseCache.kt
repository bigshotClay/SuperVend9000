package dev.supervend.llm

import dev.supervend.model.Sha256
import java.util.concurrent.ConcurrentHashMap

/**
 * Response cache keyed by (modelId, sha256(prompt)) for cacheable step types — market-price
 * research and the like (software spec §11). Only [CompletionRequest.cacheable] requests are
 * ever stored or served; everything else bypasses the cache entirely. Caching is a pure
 * memoization of deterministic-enough calls, so it never changes correctness, only cost.
 */
interface ResponseCache {
    fun get(key: Sha256): CompletionResult.Completed?
    fun put(key: Sha256, value: CompletionResult.Completed)

    object Disabled : ResponseCache {
        override fun get(key: Sha256): CompletionResult.Completed? = null
        override fun put(key: Sha256, value: CompletionResult.Completed) {}
    }
}

/** Process-lifetime in-memory cache. A run is one process (software spec §1.1), so this suffices. */
class InMemoryResponseCache : ResponseCache {
    private val map = ConcurrentHashMap<String, CompletionResult.Completed>()
    override fun get(key: Sha256): CompletionResult.Completed? = map[key.hex]
    override fun put(key: Sha256, value: CompletionResult.Completed) {
        map[key.hex] = value
    }
}
