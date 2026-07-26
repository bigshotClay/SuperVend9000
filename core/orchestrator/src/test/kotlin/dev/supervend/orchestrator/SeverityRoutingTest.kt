package dev.supervend.orchestrator

import dev.supervend.model.RecoveryVerdict
import dev.supervend.model.Severity
import dev.supervend.model.UnusableReason
import dev.supervend.recovery.RecoveryClassifier
import dev.supervend.recovery.RecoveryDecision
import dev.supervend.recovery.RecoveryOutcome
import dev.supervend.recovery.ResumePoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Severity routing (PRD R41, spec §9): NON_BLOCKING queues and continues; BLOCKING recovers + pauses. */
class SeverityRoutingTest : FunSpec({

    test("a NON_BLOCKING failure queues a task and the loop continues") {
        val world = TestWorld(day = 1)
        // RCV-03 (OrderAgingReport) is NON_BLOCKING; fail it and check the day still completes.
        val trace = orchestrator().runDay(1, world, FailingRunner("RCV-03", Fail.Unusable(UnusableReason.TRUNCATED)))

        world.tasks.map { it.first } shouldBe listOf("incident:RCV-03")
        world.tasks.single().second shouldBe Severity.NON_BLOCKING
        // The loop did not pause: later phases still ran.
        trace.counterTrace.any { it.stepId.value == "OPS-01" } shouldBe true
        trace.counterTrace.any { it.stepId.value == "CLS-03" } shouldBe true
        trace.recoveryLog shouldBe emptyList() // recovery is not invoked for NON_BLOCKING
    }

    test("a BLOCKING failure invokes recovery and pauses at the phase boundary on escalation") {
        val world = TestWorld(day = 1)
        val escalate = RecoveryClassifier { _, _ ->
            RecoveryDecision(RecoveryVerdict.ExternalStateChanged, ResumePoint.Escalate)
        }
        // RCV-01 is BLOCKING and the first RECEIVE step; failing it escalates and halts the loop
        // once the RECEIVE phase ends — OPS steps must never run.
        val trace = orchestrator(classifier = escalate)
            .runDay(1, world, FailingRunner("RCV-01", Fail.Unusable(UnusableReason.REFUSAL)))

        trace.recoveryLog.size shouldBe 1
        (trace.recoveryLog.single().outcome is RecoveryOutcome.Escalated) shouldBe true
        // Paused at the RECEIVE→OPS boundary: RECEIVE steps ran, OPS did not.
        trace.counterTrace.any { it.stepId.value == "RCV-01" } shouldBe true
        trace.counterTrace.none { it.stepId.value == "OPS-01" } shouldBe true
    }

    test("a BLOCKING failure whose recovery resolves does not pause the loop") {
        val world = TestWorld(day = 1)
        val resolve = RecoveryClassifier { incident, candidates ->
            RecoveryDecision(RecoveryVerdict.BadOutputThisStep(incident.stepDef.id), ResumePoint.At(candidates.first()))
        }
        val trace = orchestrator(classifier = resolve)
            .runDay(1, world, FailingRunner("RCV-01", Fail.Unusable(UnusableReason.REFUSAL)))

        (trace.recoveryLog.single().outcome is RecoveryOutcome.Resolved) shouldBe true
        // Resolved → loop continued past the RECEIVE phase.
        trace.counterTrace.any { it.stepId.value == "OPS-01" } shouldBe true
    }
})
