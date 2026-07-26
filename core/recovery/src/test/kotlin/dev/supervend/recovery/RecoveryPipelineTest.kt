package dev.supervend.recovery

import dev.supervend.model.IncidentId
import dev.supervend.model.PlanCounterSnapshot
import dev.supervend.model.RecoveryVerdict
import dev.supervend.model.RunId
import dev.supervend.model.Severity
import dev.supervend.model.Sha256
import dev.supervend.model.StateSnapshotRef
import dev.supervend.model.StepId
import dev.supervend.model.StepStatus
import dev.supervend.plan.Workplan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Recovery pipeline (spec §10, PRD R40): classify against candidates, cap invocations, escalate. */
class RecoveryPipelineTest : FunSpec({

    val step = Workplan.PLAN.bootstrap.first()
    fun incident() = IncidentRecord(
        id = IncidentId("INC-1"),
        day = 1,
        counter = PlanCounterSnapshot(RunId("r"), 1, 0, step.id, StepStatus.VALIDATION_FAILED),
        stepDef = step,
        preconditionResults = emptyList(),
        failure = FailureEvidence.NoArtifact,
        gateRejections = emptyList(),
        stateSnapshot = StateSnapshotRef(1, Sha256("x")),
        severity = Severity.BLOCKING,
    )

    val candidates = listOf(StepId("BOOT-01"), StepId("BOOT-02"))

    test("resolves when the agent picks a real candidate") {
        val pipe = RecoveryPipeline({ _, c -> RecoveryDecision(RecoveryVerdict.ExternalStateChanged, ResumePoint.At(c.first())) })
        val out = pipe.handle(incident(), candidates, priorInvocations = 0)
        val resolved = out.shouldBeInstanceOf<RecoveryOutcome.Resolved>()
        resolved.decision.resumeAt shouldBe ResumePoint.At(StepId("BOOT-01"))
    }

    test("escalates when the chosen resume point is not a harness-offered candidate") {
        val pipe = RecoveryPipeline({ _, _ -> RecoveryDecision(RecoveryVerdict.ExternalStateChanged, ResumePoint.At(StepId("NOPE"))) })
        val out = pipe.handle(incident(), candidates, priorInvocations = 0).shouldBeInstanceOf<RecoveryOutcome.Escalated>()
        out.reason shouldBe EscalationReason.RESUME_NOT_A_CANDIDATE
    }

    test("escalates when the agent itself chooses to escalate") {
        val pipe = RecoveryPipeline({ _, _ -> RecoveryDecision(RecoveryVerdict.PreconditionNeverEstablished, ResumePoint.Escalate) })
        pipe.handle(incident(), candidates, 0).shouldBeInstanceOf<RecoveryOutcome.Escalated>()
            .reason shouldBe EscalationReason.NO_CANDIDATE_FITS
    }

    test("escalates once the per-incident invocation cap is reached (default 2)") {
        var calls = 0
        val pipe = RecoveryPipeline({ _, c -> calls++; RecoveryDecision(RecoveryVerdict.ExternalStateChanged, ResumePoint.At(c.first())) })
        pipe.handle(incident(), candidates, priorInvocations = 0).shouldBeInstanceOf<RecoveryOutcome.Resolved>()
        pipe.handle(incident(), candidates, priorInvocations = 1).shouldBeInstanceOf<RecoveryOutcome.Resolved>()
        val capped = pipe.handle(incident(), candidates, priorInvocations = 2).shouldBeInstanceOf<RecoveryOutcome.Escalated>()
        capped.reason shouldBe EscalationReason.CAP_EXHAUSTED
        calls shouldBe 2 // the classifier is never consulted once capped
    }
})
