package dev.supervend.orchestrator

import dev.supervend.llm.ContextPackage
import dev.supervend.llm.PromptBuilder
import dev.supervend.model.CheckResult
import dev.supervend.model.FailureReport
import dev.supervend.model.RecoveryVerdict
import dev.supervend.model.ValidationResult
import dev.supervend.recovery.FailureEvidence
import dev.supervend.recovery.RecoveryClassifier
import dev.supervend.recovery.RecoveryDecision
import dev.supervend.recovery.ResumePoint
import dev.supervend.validate.ValidatorRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Incident replay (PRD R39, spec §10) and the scrubbed-retry contract (CLAUDE.md #6, PRD R12): both
 * rest on determinism. An incident's captured evidence re-validates bit-identically; a retry can
 * carry only a failure report, never the failed output, and that is enforced by the type system.
 */
class ReplayAndRetryTest : FunSpec({

    val escalate = RecoveryClassifier { _, _ ->
        RecoveryDecision(RecoveryVerdict.ExternalStateChanged, ResumePoint.Escalate)
    }

    test("revalidate reproduces an incident's validation verdict bit-identically") {
        val world = TestWorld(day = 0)
        val badJson = """{"wrong":true}"""
        val trace = orchestrator(classifier = escalate)
            .runBootstrap(world, FailingRunner("BOOT-01", Fail.BadJson(badJson)))

        val incident = trace.incidentLog.single { it.stepDef.id.value == "BOOT-01" }
        val evidence = incident.failure.shouldBeInstanceOf<FailureEvidence.ValidationFailure>()

        // Re-run the *same* pure validator on the *same* captured artifact JSON — verdict must match.
        val replay = ValidatorRegistry.DEFAULT.require("AssortmentPlan").validate(badJson, world)
        val replayReport = replay.shouldBeInstanceOf<ValidationResult.Fail>()
        replayReport.report shouldBe evidence.report
    }

    test("the incident artifact ref is content-addressed by the failed JSON") {
        val world = TestWorld(day = 0)
        val badJson = """{"wrong":true}"""
        val trace = orchestrator(classifier = escalate)
            .runBootstrap(world, FailingRunner("BOOT-01", Fail.BadJson(badJson)))
        val evidence = trace.incidentLog.single { it.stepDef.id.value == "BOOT-01" }
            .failure.shouldBeInstanceOf<FailureEvidence.ValidationFailure>()
        evidence.artifact.sha256 shouldBe dev.supervend.model.Hashing.sha256(badJson)
    }

    test("a retry prompt carries the failure report only — never the failed output") {
        val pkg = ContextPackage(stateBrief = "cash 500", inputs = "none", creed = "ROLE: x", outputSchema = "{}")
        // A retry is built from the *original* package plus a structured report. There is no
        // parameter through which the model's rejected text could be threaded (AttemptContext.Again
        // and PromptBuilder.retry both take only a FailureReport) — this test documents that.
        val report = FailureReport(listOf(CheckResult("lines", false, "non-empty", "empty")))
        val retry = PromptBuilder.retry(pkg, report)

        retry shouldContain "lines"
        retry shouldContain "cash 500"                      // original package is preserved
        retry shouldNotContain "THE MODELS PRIOR GARBAGE"   // no channel exists to include it

        // AttemptContext.Again's only payload is the prior FailureReport (structural guarantee).
        val again = AttemptContext.Again(attemptIndex = 1, priorFailure = report)
        again.priorFailure shouldBe report
    }
})
