package dev.supervend.orchestrator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Stall detection (PRD R42): the two independent liveness counters and their reset semantics. */
class StallDetectorTest : FunSpec({

    test("N consecutive failures trips CONSECUTIVE_FAILURES and resets") {
        val d = StallDetector(n = 3, m = 100)
        d.record(failed = true, economicApplied = 0) shouldBe null
        d.record(failed = true, economicApplied = 0) shouldBe null
        d.record(failed = true, economicApplied = 0) shouldBe StallTrigger.CONSECUTIVE_FAILURES
        // After the reset the counter is clean again.
        d.record(failed = true, economicApplied = 0) shouldBe null
    }

    test("a non-failed step resets the consecutive-failure counter") {
        val d = StallDetector(n = 3, m = 100)
        d.record(failed = true, economicApplied = 0)
        d.record(failed = true, economicApplied = 0)
        d.record(failed = false, economicApplied = 0) shouldBe null // success breaks the streak
        d.record(failed = true, economicApplied = 0) shouldBe null
        d.record(failed = true, economicApplied = 0) shouldBe null
        d.record(failed = true, economicApplied = 0) shouldBe StallTrigger.CONSECUTIVE_FAILURES
    }

    test("M steps without an economic action trips NO_ECONOMIC_ACTION regardless of success") {
        val d = StallDetector(n = 100, m = 5)
        repeat(4) { d.record(failed = false, economicApplied = 0) shouldBe null }
        d.record(failed = false, economicApplied = 0) shouldBe StallTrigger.NO_ECONOMIC_ACTION
    }

    test("an economic action resets the no-economy counter") {
        val d = StallDetector(n = 100, m = 3)
        d.record(failed = false, economicApplied = 0)
        d.record(failed = false, economicApplied = 1) shouldBe null // money moved → reset
        d.record(failed = false, economicApplied = 0)
        d.record(failed = false, economicApplied = 0) shouldBe null
    }

    test("orchestrator emits a NO_ECONOMIC_ACTION reset when no step moves money") {
        val world = TestWorld(day = 1)
        val orch = orchestrator(stall = StallDetector(n = 100, m = 15))
        orch.runBootstrap(world, NoEconomyRunner())
        val trace = orch.runDay(1, world, NoEconomyRunner())
        val reset = trace.stallResets.firstOrNull { it.trigger == StallTrigger.NO_ECONOMIC_ACTION }
        reset.shouldNotBeNull()
        // The reset is also visible in the counter trace as a RESET marker.
        trace.counterTrace.any { it.status == dev.supervend.model.StepStatus.RESET } shouldBe true
    }
})
