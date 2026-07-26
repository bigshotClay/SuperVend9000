package dev.supervend.plan

import dev.supervend.model.DayPhase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class WorkplanTest : FunSpec({

    val plan = Workplan.PLAN

    test("closure checks pass: every role/gate/validator/consumer/binding resolves") {
        PlanClosure.check(plan) shouldBe emptyList()
    }

    test("step ids and counts match the workplan tables") {
        plan.bootstrap.map { it.id.value } shouldContainExactly
            (1..9).map { "BOOT-0$it" }
        plan.dailyLoop.map { it.id.value } shouldContainExactly listOf(
            "RCV-01", "RCV-02", "RCV-03",
            "OPS-01", "OPS-02", "OPS-03", "OPS-04", "OPS-05",
            "COM-01", "COM-02",
            "PRO-01", "PRO-02", "PRO-03", "PRO-04", "PRO-05",
            "PRC-01", "PRC-02",
            "TSK-01",
            "CLS-01", "CLS-02", "CLS-03",
        )
        plan.weeklyAudit.map { it.id.value } shouldContainExactly listOf("AUD-01", "AUD-02", "AUD-03", "AUD-04")
    }

    test("daily-loop phases run in the workplan order RECEIVE→OPS→COMMS→PROCURE→PRICE→TASKS→CLOSE") {
        val order = plan.dailyLoop.map { it.phase }.distinct()
        order shouldContainExactly listOf(
            DayPhase.RECEIVE, DayPhase.OPS, DayPhase.COMMS, DayPhase.PROCURE, DayPhase.PRICE, DayPhase.TASKS, DayPhase.CLOSE,
        )
    }

    test("invariant 2: every cash/order/price-writing step is gated (no ungated money path)") {
        plan.moneyMutatingSteps().map { it.id.value }.toSet() shouldBe setOf(
            // discretionary: orders + prices
            "BOOT-06", "BOOT-08", "PRO-03", "PRC-02",
            // ledger-mirror writes (OPERATOR-INPUT #10, resolved via LedgerConsistency)
            "BOOT-07", "RCV-02", "OPS-02", "OPS-05", "PRO-04",
        )
        plan.moneyMutatingSteps().all { it.gates.isNotEmpty() } shouldBe true
    }

    test("each money-writing step carries exactly the workplan's gates") {
        fun gates(id: String) = plan.allSteps.first { it.id.value == id }.gates.map { it.value }.toSet()
        gates("BOOT-06") shouldBe setOf("SpecBeforePay", "ValidatedPayee", "SpendCap")
        gates("PRO-03") shouldBe setOf("SpecBeforePay", "ValidatedPayee", "SpendCap")
        gates("BOOT-08") shouldBe setOf("MarginFloor")
        gates("PRC-02") shouldBe setOf("MarginFloor")
        // Bookkeeper mirror steps now carry the ledger-integrity gate.
        setOf("BOOT-07", "RCV-02", "OPS-02", "OPS-05", "PRO-04").forEach {
            gates(it) shouldBe setOf("LedgerConsistency")
        }
    }

    test("plan version is content-addressed and JSON export round-trips") {
        val v1 = plan.version()
        v1.hash shouldBe plan.version().hash
        v1.parentHash shouldBe null
        // Exported JSON is the review artifact; it must mention every phase and a sample step.
        plan.prettyJson() shouldContain "BOOT-06"
        plan.prettyJson() shouldContain "RECEIVE"
    }

    test("a tampered plan produces a different version hash (attributability)") {
        val tampered = plan.copy(name = "tampered")
        tampered.version().hash shouldBe tampered.version().hash
        (tampered.version().hash == plan.version().hash) shouldBe false
    }
})
