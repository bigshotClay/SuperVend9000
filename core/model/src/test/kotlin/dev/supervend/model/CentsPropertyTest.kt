package dev.supervend.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * Cents is exact integer money — no Double anywhere (CLAUDE.md #2). These properties pin the
 * arithmetic laws the ledger and gates rely on.
 */
class CentsPropertyTest : FunSpec({

    // Keep magnitudes well within Long range so intermediate products never overflow.
    val amt = Arb.long(-1_000_000_000L..1_000_000_000L)
    val units = Arb.int(-100_000..100_000)

    test("addition is commutative") {
        checkAll(amt, amt) { a, b ->
            (Cents(a) + Cents(b)) shouldBe (Cents(b) + Cents(a))
        }
    }

    test("addition is associative") {
        checkAll(amt, amt, amt) { a, b, c ->
            ((Cents(a) + Cents(b)) + Cents(c)) shouldBe (Cents(a) + (Cents(b) + Cents(c)))
        }
    }

    test("ZERO is the additive identity") {
        checkAll(amt) { a ->
            (Cents(a) + Cents.ZERO) shouldBe Cents(a)
        }
    }

    test("minus is the inverse of plus") {
        checkAll(amt, amt) { a, b ->
            ((Cents(a) + Cents(b)) - Cents(b)) shouldBe Cents(a)
        }
    }

    test("unary minus negates") {
        checkAll(amt) { a ->
            (-Cents(a)) shouldBe Cents(-a)
            (Cents(a) + (-Cents(a))) shouldBe Cents.ZERO
        }
    }

    test("times by units equals repeated addition semantics") {
        checkAll(amt, units) { a, n ->
            (Cents(a) * n) shouldBe Cents(a * n)
        }
    }

    test("sum equals fold of plus") {
        checkAll(Arb.long(-1000L..1000L), Arb.long(-1000L..1000L), Arb.long(-1000L..1000L)) { a, b, c ->
            Cents.sum(listOf(Cents(a), Cents(b), Cents(c))) shouldBe (Cents(a) + Cents(b) + Cents(c))
        }
    }

    test("compareTo agrees with Long ordering") {
        checkAll(amt, amt) { a, b ->
            Cents(a).compareTo(Cents(b)) shouldBe a.compareTo(b)
        }
    }

    test("scaleBps truncates toward zero and stays integer") {
        // 20% markup: 10_000 * 12_000 / 10_000 = 12_000
        Cents(10_000).scaleBps(BasisPoints(12_000)) shouldBe Cents(12_000)
        // 150 bp of 100 cents = 100*150/10000 = 1 (truncated from 1.5)
        Cents(100).scaleBps(BasisPoints(150)) shouldBe Cents(1)
    }
})
