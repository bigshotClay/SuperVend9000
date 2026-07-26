package dev.supervend.orchestrator

import dev.supervend.model.ActionType
import dev.supervend.model.Cents
import dev.supervend.model.ProposedAction
import dev.supervend.plan.StepDef

/**
 * A deterministic, zero-network [StepRunner] standing in for the persona dispatch. Every seat —
 * script or model — is simulated by emitting a valid artifact for the step's output schema plus the
 * write intents a real seat would submit. This is what lets the whole orchestrator be exercised with
 * MockModel-equivalent determinism (CLAUDE.md testing requirements): the golden trace is a pure
 * function of the plan.
 */
internal class GoldenRunner : StepRunner {
    override fun run(step: StepDef, day: Int, attempt: AttemptContext): AttemptResult =
        AttemptResult.Produced(artifactJson = goldenArtifact(step.outputSchema.id), actions = actionsFor(step))
}

/** Valid JSON per output schema; substantive artifacts get real content, structural ones an object. */
internal fun goldenArtifact(schemaId: String): String = when (schemaId) {
    "AssortmentPlan" -> """{"lines":[{"product":"COLA","parLevel":30,"reorderPoint":10}]}"""
    "PurchaseOrder" -> """{"supplier":"SUP-HC","product":"COLA","units":30,"unitCostCents":70,"specArtifact":"spec-abc123"}"""
    "Quote" -> """{"supplier":"SUP-HC","product":"COLA","unitCostCents":70,"units":30}"""
    "AmendmentProposal" -> """{"amendments":[{"type":"PriceBandAdjust","product":"COLA","deltaBps":500}]}"""
    else -> "{}"
}

/** The write intents each money-mutating step submits; all satisfy the [TestWorld] gate facts. */
internal fun actionsFor(step: StepDef): List<ProposedAction> = when (step.id.value) {
    "BOOT-06", "PRO-03" -> listOf(ProposedAction.PlaceOrder(SUP, COLA, units = 1, unitCost = Cents(70)))
    "BOOT-08", "PRC-02" -> listOf(ProposedAction.SetPrice(COLA, Cents(200)))
    "BOOT-07", "OPS-02", "PRO-04" -> listOf(ProposedAction.WriteLedger(ActionType.WRITE_CASH_LEDGER, claimed = 100, source = 100))
    "RCV-02", "OPS-05" -> listOf(ProposedAction.WriteLedger(ActionType.WRITE_INVENTORY_LEDGER, claimed = 100, source = 100))
    else -> emptyList()
}
