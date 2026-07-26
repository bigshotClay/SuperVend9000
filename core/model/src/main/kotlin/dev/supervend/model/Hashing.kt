package dev.supervend.model

import java.security.MessageDigest

/**
 * Content hashing (CLAUDE.md #11: every threshold and config is a hashed artifact). Pure and
 * deterministic — the same bytes always yield the same [Sha256]. No clock, no randomness, so
 * hashes are reproducible across runs and machines.
 */
object Hashing {
    fun sha256(bytes: ByteArray): Sha256 {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256(digest.joinToString("") { "%02x".format(it) })
    }

    fun sha256(text: String): Sha256 = sha256(text.encodeToByteArray())
}
