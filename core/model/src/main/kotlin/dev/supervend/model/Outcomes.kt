package dev.supervend.model

import kotlinx.serialization.Serializable

/**
 * The §3 type-system backbone. Every closed set here is `sealed` so that adding a new
 * variant breaks the build until every consumer handles it (software spec §1) — the
 * type-system equivalent of gate discipline.
 */

@Serializable
sealed interface StepOutcome {
    @Serializable data class Success(val artifactRef: ArtifactRef) : StepOutcome
    @Serializable data class ValidationFailed(val report: FailureReport, val artifactRef: ArtifactRef) : StepOutcome
    @Serializable data class PreconditionMismatch(val expected: List<Predicate>, val observed: StateSnapshotRef) : StepOutcome
    /** truncation, non-JSON, refusal — the model produced nothing usable. */
    @Serializable data class AgentUnusable(val reason: UnusableReason) : StepOutcome
}

@Serializable
enum class UnusableReason { TRUNCATED, NON_JSON, REFUSAL, EMPTY }

@Serializable
sealed interface GateDecision {
    @Serializable data object Allow : GateDecision
    @Serializable data class Reject(
        val gateId: GateId,
        val reason: String,
        /** Sized for direct inclusion in agent retry context (software spec §6). */
        val expected: String,
        val observed: String,
    ) : GateDecision
}

@Serializable
enum class Severity { BLOCKING, NON_BLOCKING }

@Serializable
sealed interface RecoveryVerdict {
    @Serializable data class BadOutputThisStep(val stepId: StepId) : RecoveryVerdict
    @Serializable data class BadInputFromPrior(val producingStep: StepId) : RecoveryVerdict
    @Serializable data object PreconditionNeverEstablished : RecoveryVerdict
    @Serializable data object ExternalStateChanged : RecoveryVerdict
}

@Serializable
sealed interface SupplierReplyClass {
    @Serializable data class Acceptance(val quote: QuoteRef) : SupplierReplyClass
    @Serializable data class CounterWithinBand(val quote: QuoteRef) : SupplierReplyClass
    @Serializable data class CounterAboveWalkAway(val quote: QuoteRef) : SupplierReplyClass
    @Serializable data object Refusal : SupplierReplyClass
    @Serializable data class ScamSignal(val indicators: List<String>) : SupplierReplyClass
    /** Mandatory escape valve (PRD R30): never forces a fit, routes to a clarify template. */
    @Serializable data object Ambiguous : SupplierReplyClass
}

// ---- Validation result payloads (software spec §7) ----

@Serializable
sealed interface ValidationResult {
    @Serializable data object Pass : ValidationResult
    @Serializable data class Fail(val report: FailureReport) : ValidationResult
}

@Serializable
data class FailureReport(val checks: List<CheckResult>) {
    val passed: Boolean get() = checks.all { it.passed }
}

@Serializable
data class CheckResult(
    val id: String,
    val passed: Boolean,
    val expected: String,
    val observed: String,
)
