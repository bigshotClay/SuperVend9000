package dev.supervend.plan

import dev.supervend.model.ActionType
import dev.supervend.model.Hashing
import dev.supervend.model.Sha256
import dev.supervend.model.StepId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The whole operating plan (Master Workplan v1). Three segments: [bootstrap] (Day 0, once),
 * [dailyLoop] (Day ≥ 1), and [weeklyAudit] (replaces the TASKS phase every 7th day). The plan
 * depends only on roles, predicates, gates, and validators in their registries (workplan §6
 * invariant 4); the closure checks enforce that.
 */
@Serializable
data class Plan(
    val name: String,
    val bootstrap: List<StepDef>,
    val dailyLoop: List<StepDef>,
    val weeklyAudit: List<StepDef>,
) {
    val allSteps: List<StepDef> get() = bootstrap + dailyLoop + weeklyAudit

    /** Steps that write cash, orders, or prices — every one must be gated (workplan §6.2). */
    fun moneyMutatingSteps(): List<StepDef> = allSteps.filter { step ->
        step.mandate.allowed.any {
            it == ActionType.PLACE_ORDER || it == ActionType.SET_PRICE ||
                it == ActionType.WRITE_CASH_LEDGER || it == ActionType.WRITE_INVENTORY_LEDGER
        }
    }

    fun canonicalJson(): String = CANONICAL.encodeToString(serializer(), this)
    fun prettyJson(): String = PRETTY.encodeToString(serializer(), this)

    /** Content hash → the `PlanVersion` genesis identity (workplan header). */
    fun version(parent: Sha256? = null): PlanVersion =
        PlanVersion(hash = Hashing.sha256(canonicalJson()), parentHash = parent, json = canonicalJson())

    fun stepIds(): Set<StepId> = allSteps.map { it.id }.toSet()

    companion object {
        private val CANONICAL = Json { prettyPrint = false; encodeDefaults = true }
        private val PRETTY = Json { prettyPrint = true; encodeDefaults = true }
    }
}

/** A content-addressed, immutable plan version; amendments produce children (software spec §4.4). */
@Serializable
data class PlanVersion(val hash: Sha256, val parentHash: Sha256?, val json: String)
