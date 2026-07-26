package dev.supervend.validate

import dev.supervend.model.CheckResult
import dev.supervend.model.FailureReport
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import dev.supervend.model.ValidationResult
import dev.supervend.model.ValidatorRef

/**
 * A registered validator (software spec §7). Operates on the artifact's raw JSON so the schema
 * layer (deserialization) is part of validation. Three layers in order: (1) schema conformance,
 * (2) referential integrity against [ValidationState], (3) business assertions. Pure and
 * versioned — same JSON + same validator version ⇒ same verdict (CLAUDE.md #4, PRD R11a), so
 * `harness validate` re-runs bit-identically to what the run saw.
 */
interface ArtifactValidator {
    val ref: ValidatorRef
    fun validate(json: String, state: ValidationState): ValidationResult
}

/** The read surface validators use for referential checks (does this id resolve in state?). */
interface ValidationState {
    fun productExists(product: ProductId): Boolean
    fun supplierExists(supplier: SupplierId): Boolean

    companion object {
        /** Permissive default for schema/business-only fixtures where referential context is irrelevant. */
        val ANY = object : ValidationState {
            override fun productExists(product: ProductId) = true
            override fun supplierExists(supplier: SupplierId) = true
        }
    }
}

/** Small builder so validators read as a checklist; the first failure short-circuits deserialization. */
internal class Checks {
    private val results = mutableListOf<CheckResult>()

    fun assert(id: String, ok: Boolean, expected: String, observed: String) {
        results += CheckResult(id, ok, expected, observed)
    }

    fun toResult(): ValidationResult {
        val report = FailureReport(results)
        return if (report.passed) ValidationResult.Pass else ValidationResult.Fail(report)
    }

    companion object {
        /** A single schema-layer failure (deserialization threw). */
        fun schemaFail(detail: String): ValidationResult = ValidationResult.Fail(
            FailureReport(listOf(CheckResult("schema", false, "deserializable JSON for the artifact type", detail))),
        )
    }
}
