package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * Design rule (software spec §3.1): no stringly-typed identifiers. Every identifier is a
 * value class so the compiler forbids passing a [SupplierId] where a [ProductId] is meant.
 */

@JvmInline @Serializable value class RunId(val value: String)
@JvmInline @Serializable value class StepId(val value: String)
@JvmInline @Serializable value class SupplierId(val value: String)
@JvmInline @Serializable value class ProductId(val value: String)
@JvmInline @Serializable value class OrderId(val value: String)
@JvmInline @Serializable value class QuoteId(val value: String)
@JvmInline @Serializable value class IncidentId(val value: String)
@JvmInline @Serializable value class GateId(val value: String)
@JvmInline @Serializable value class LedgerId(val value: String)
@JvmInline @Serializable value class RoleId(val value: String)
@JvmInline @Serializable value class SlotId(val value: String)
@JvmInline @Serializable value class TaskId(val value: String)

/** A content hash (sha256 hex) identifying an immutable artifact/version. */
@JvmInline @Serializable value class Sha256(val hex: String)

/** Reference to a JSON schema generated from a `@Serializable` type (never hand-written). */
@JvmInline @Serializable value class SchemaRef(val id: String)

/** Registered validator identity: id + semantic version. */
@Serializable data class ValidatorRef(val id: String, val version: SemVer)

/** Registered pure-function reference (script staffing / script step). */
@JvmInline @Serializable value class FunctionRef(val id: String)

@Serializable data class SemVer(val major: Int, val minor: Int, val patch: Int) {
    override fun toString(): String = "$major.$minor.$patch"
}
