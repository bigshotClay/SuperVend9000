package dev.supervend.llm.qualify

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

class BatteryTest : FunSpec({

    val battery = Battery.default

    test("battery spans at least 30 cases across all four categories (personas §7)") {
        battery.cases.size shouldBeGreaterThanOrEqual 30
        battery.cases.map { it.category }.toSet() shouldBe BatteryCase.Category.entries.toSet()
    }

    test("every case renders instructions at the bottom of the package (CLAUDE.md #8)") {
        for (c in battery.cases) {
            val rendered = c.pkg.render()
            val instr = rendered.indexOf("=== INSTRUCTIONS ===")
            val state = rendered.indexOf("=== STATE BRIEF ===")
            val inputs = rendered.indexOf("=== INPUTS ===")
            (state < inputs) shouldBe true
            (inputs < instr) shouldBe true // instructions come after brief and inputs
        }
    }

    test("battery hash is stable and content-addressed") {
        battery.hash() shouldBe Battery.default.hash()
    }

    test("a perfect model qualifies (>90%)") {
        val report = battery.run(BatteryModels.perfect(battery), "mock-perfect")
        report.score shouldBe 1.0
        report.qualified.shouldBeTrue()
    }

    test("a model at exactly the threshold does NOT qualify (>90% is strict)") {
        // 90% of 32 = 28.8; 29/32 = 0.906 qualifies, 28/32 = 0.875 does not. Pin just below.
        val justBelow = (battery.cases.size * 0.90).toInt() // 28
        val report = battery.run(BatteryModels.partial(battery, justBelow), "mock-partial")
        report.passed shouldBe justBelow
        report.qualified.shouldBeFalse()
    }

    test("a strong-but-imperfect model above threshold qualifies") {
        val report = battery.run(BatteryModels.partial(battery, battery.cases.size - 1), "mock-strong")
        report.qualified.shouldBeTrue()
    }

    test("the mandatory-Ambiguous case is present") {
        val ambiguous = battery.cases.filter { it.category == BatteryCase.Category.ENUM_CLASSIFICATION }
            .any { it.canonicalAnswer.contains("AMBIGUOUS") }
        ambiguous.shouldBeTrue()
    }
})
