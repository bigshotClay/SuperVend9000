package dev.supervend.pricing

import dev.supervend.model.Cents
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class PricingTest : FunSpec({

    // ---- Cost basis (PRD R31) ----
    test("weighted-average cost is exact integer arithmetic") {
        val wac = CostBasis.weightedAverage(listOf(CostBasis.Lot(10, Cents(70)), CostBasis.Lot(30, Cents(90))))
        wac shouldBe Cents(85) // (700 + 2700) / 40
    }

    test("empty lot history yields zero cost basis") {
        CostBasis.weightedAverage(emptyList()) shouldBe Cents.ZERO
    }

    // ---- Probe schedule (PRD R33) ----
    test("probe schedule lays out symmetric ±step levels at the dwell cadence") {
        val points = ProbeSchedule().points(Cents(1000))
        points.map { it.price } shouldBe listOf(Cents(950), Cents(1000), Cents(1050))
        points.map { it.dayOffset } shouldBe listOf(0, 7, 14)
    }

    // ---- Elasticity regression against synthetic known-elasticity data (DoD) ----
    test("recovers unit elasticity from exact q = C/p data") {
        // C = 12000, e = 1: every point lies exactly on the log-log line.
        val obs = listOf(100 to 120, 120 to 100, 150 to 80, 200 to 60, 300 to 40)
            .map { Elasticity.Observation(Cents(it.first.toLong()), it.second) }
        val est = Elasticity.estimate(obs)
        est.elasticity shouldBe (1.0 plusOrMinus 1e-9)
        est.levels shouldBe 5
        est.converged shouldBe true
        est.ciHalfWidth shouldBeLessThan 1e-6
    }

    test("recovers elasticity 2 from exact q = C/p^2 data") {
        // C = 1_440_000, e = 2.
        val obs = listOf(100 to 144, 120 to 100, 200 to 36, 60 to 400)
            .map { Elasticity.Observation(Cents(it.first.toLong()), it.second) }
        val est = Elasticity.estimate(obs)
        abs(est.elasticity - 2.0) shouldBeLessThan 1e-9
        est.converged shouldBe true
    }

    test("fewer than three levels never counts as converged") {
        val obs = listOf(Elasticity.Observation(Cents(100), 120), Elasticity.Observation(Cents(200), 60))
        val est = Elasticity.estimate(obs)
        est.elasticity shouldBe (1.0 plusOrMinus 1e-9)
        est.levels shouldBe 2
        est.converged shouldBe false // ≥3 levels required before it may drive a price change
    }

    test("noisy data widens the interval and blocks convergence") {
        // Same three price levels but quantities off the line → non-zero residual → wide CI.
        val obs = listOf(
            Elasticity.Observation(Cents(100), 120),
            Elasticity.Observation(Cents(150), 105), // off-trend
            Elasticity.Observation(Cents(300), 40),
        )
        Elasticity.estimate(obs).converged shouldBe false
    }
})
