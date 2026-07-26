package dev.supervend.audit

import dev.supervend.model.Hashing
import dev.supervend.plan.Plan
import dev.supervend.plan.PlanVersion
import dev.supervend.validate.Amendment
import dev.supervend.validate.AmendmentProposal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The git-like plan-version chain (software spec §4.4, §10, workplan AUD-03). Applying a bounded
 * amendment produces a child [PlanEpoch] whose [PlanVersion] carries the parent hash — all versions
 * retained, every one content-addressed and attributable. Structural plan rewrites are impossible by
 * construction: an [Amendment] can only adjust pre-registered parameters, so the plan *structure*
 * never changes; the epoch identity folds in the cumulative amendments so the chain still advances.
 */
object PlanLineage {

    /** Genesis epoch: the authored plan, no amendments, no parent. */
    fun genesis(plan: Plan): PlanEpoch {
        val version = PlanVersion(
            hash = epochHash(plan, emptyList()),
            parentHash = null,
            json = amendmentsJson(emptyList()),
        )
        return PlanEpoch(version, appliedAmendments = emptyList())
    }

    /**
     * Apply a proposal against [parent] under [bounds]. Accepted amendments (capped at
     * [AmendmentBounds.maxPerAudit]) advance the chain to a new child epoch; rejected ones are logged
     * with reasons and never applied. If nothing is accepted the chain does not advance (the parent
     * epoch is returned unchanged) — books, and plans, are never plugged.
     */
    fun apply(
        plan: Plan,
        parent: PlanEpoch,
        proposal: AmendmentProposal,
        bounds: AmendmentBounds = AmendmentBounds.default(),
    ): AmendmentApplication {
        val accepted = mutableListOf<Amendment>()
        val rejected = mutableListOf<RejectedAmendment>()
        for (a in proposal.amendments) {
            if (accepted.size >= bounds.maxPerAudit) {
                rejected += RejectedAmendment(a, "per-audit cap ${bounds.maxPerAudit} reached")
                continue
            }
            when (val v = bounds.check(a)) {
                is AmendmentVerdict.Accepted -> accepted += a
                is AmendmentVerdict.Rejected -> rejected += RejectedAmendment(a, v.reason)
            }
        }
        if (accepted.isEmpty()) {
            return AmendmentApplication(epoch = parent, applied = emptyList(), rejected = rejected)
        }
        val cumulative = parent.appliedAmendments + accepted
        val child = PlanEpoch(
            version = PlanVersion(
                hash = epochHash(plan, cumulative),
                parentHash = parent.version.hash,
                json = amendmentsJson(cumulative),
            ),
            appliedAmendments = cumulative,
        )
        return AmendmentApplication(epoch = child, applied = accepted, rejected = rejected)
    }

    /** Epoch identity = plan structure + cumulative amendments; deterministic and content-addressed. */
    private fun epochHash(plan: Plan, cumulative: List<Amendment>) =
        Hashing.sha256(plan.canonicalJson() + "|" + amendmentsJson(cumulative))

    private fun amendmentsJson(amendments: List<Amendment>): String =
        CANONICAL.encodeToString(kotlinx.serialization.builtins.ListSerializer(Amendment.serializer()), amendments)

    private val CANONICAL = Json { prettyPrint = false; encodeDefaults = true }
}

/** One node in the plan-version chain: a version plus the cumulative amendments that produced it. */
@Serializable
data class PlanEpoch(val version: PlanVersion, val appliedAmendments: List<Amendment>)

/** The outcome of applying a proposal: the (possibly unchanged) epoch, and what was applied/rejected. */
@Serializable
data class AmendmentApplication(
    val epoch: PlanEpoch,
    val applied: List<Amendment>,
    val rejected: List<RejectedAmendment>,
)

@Serializable
data class RejectedAmendment(val amendment: Amendment, val reason: String)
