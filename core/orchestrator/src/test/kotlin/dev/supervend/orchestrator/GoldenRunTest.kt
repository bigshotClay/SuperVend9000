package dev.supervend.orchestrator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Stage 5 DoD: MockModel-equivalent golden runs — a full day and a full week — with committed
 * golden files for the PlanCounter trace, gate log, and incident log (all carried in [RunTrace]).
 * A clean run makes zero incidents, fires every money gate, and never stalls; the committed JSON is
 * the byte-stable proof.
 */
class GoldenRunTest : FunSpec({

    test("full day (bootstrap + Day 1) matches the committed golden") {
        val world = TestWorld(day = 1)
        val trace = orchestrator().let {
            it.runBootstrap(world, GoldenRunner())
            it.runDay(1, world, GoldenRunner())
        }
        // A clean day: no failures, no stalls, but gates were actually exercised.
        trace.incidentLog shouldBe emptyList()
        trace.stallResets shouldBe emptyList()
        (trace.gateLog.isNotEmpty()) shouldBe true
        trace.gateLog.all { it.allowed } shouldBe true
        assertGolden("day.json", trace.canonicalJson())
    }

    test("full week (bootstrap + Days 1-7, audit on Day 7) matches the committed golden") {
        val world = TestWorld(day = 1)
        val trace = orchestrator().let {
            it.runBootstrap(world, GoldenRunner())
            it.runWeek(1, world, GoldenRunner())
        }
        trace.incidentLog shouldBe emptyList()
        trace.stallResets shouldBe emptyList()
        assertGolden("week.json", trace.canonicalJson())
    }

    test("every money-mutating plan step fired at least one gate in the day run") {
        val world = TestWorld(day = 1)
        val orch = orchestrator()
        orch.runBootstrap(world, GoldenRunner())
        val trace = orch.runDay(1, world, GoldenRunner())
        val gatedSteps = trace.gateLog.map { it.stepId.value }.toSet()
        // The nine money-mutating steps of the plan all appear in the gate log.
        setOf("BOOT-06", "BOOT-08", "PRO-03", "PRC-02", "BOOT-07", "RCV-02", "OPS-02", "OPS-05", "PRO-04")
            .all { it in gatedSteps } shouldBe true
    }
})
