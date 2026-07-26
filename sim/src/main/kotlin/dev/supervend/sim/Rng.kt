package dev.supervend.sim

/**
 * Deterministic RNG (SplitMix64). The sim must be bit-reproducible given a seed (PRD R2), so
 * we never touch `Math.random`, `java.util.Random` (whose algorithm we don't want to depend
 * on), or any clock. Streams are derived from (seed, day, purpose) by mixing, so the demand
 * roll on day 5 is independent of how many supplier emails were generated on day 4 — order
 * of consumption across purposes cannot perturb an unrelated result.
 */
class Rng(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform double in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / (1L shl 53))

    /** Uniform int in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return (nextDouble() * bound).toInt().coerceAtMost(bound - 1)
    }

    /** Symmetric noise in [-magnitude, +magnitude). */
    fun nextNoise(magnitude: Double): Double = (nextDouble() * 2.0 - 1.0) * magnitude

    companion object {
        /** Derive an independent stream. Purpose is a stable string key mixed into the seed. */
        fun stream(seed: Long, day: Int, purpose: String): Rng {
            var h = seed
            h = mix(h xor (day.toLong() * -0x61c8864680b583ebL))
            for (c in purpose) h = mix(h xor c.code.toLong())
            return Rng(h)
        }

        private fun mix(x: Long): Long {
            var z = x + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
