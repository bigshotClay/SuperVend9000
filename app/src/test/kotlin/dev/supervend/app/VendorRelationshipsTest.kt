package dev.supervend.app

import dev.supervend.app.harness.SimWorld
import dev.supervend.app.harness.VendorRelationships
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val P = ProductId("COLA")
private val V = SupplierId("SUP-X")

/** Tit-for-tat vendor trust: verify→trust, trust→verify on a bad trade, drop on a 2nd defection. */
class VendorRelationshipsTest : FunSpec({

    // maturity 5 days; a trade scores once today - orderDay >= 5.
    fun rel() = VendorRelationships(maturityDays = 5, goodRatio = 0.95, dropAfterStrikes = 2)

    fun order(r: VendorRelationships, id: String, day: Int, units: Int, delivered: Int) {
        r.recordOrder(SimWorld.OrderRec(id, V, P, units, day))
        if (delivered > 0) r.recordDelivery(id, delivered)
    }

    test("a new vendor starts on probation") {
        rel().state(V, today = 1) shouldBe VendorRelationships.Trust.PROBATION
    }

    test("a good trade earns trust (verify → trust)") {
        val r = rel()
        order(r, "o1", day = 0, units = 10, delivered = 10)
        r.state(V, today = 10) shouldBe VendorRelationships.Trust.TRUSTED
    }

    test("a bad trade withdraws trust (trust → verify)") {
        val r = rel()
        order(r, "o1", day = 0, units = 10, delivered = 10) // earn trust
        r.state(V, today = 10) shouldBe VendorRelationships.Trust.TRUSTED
        order(r, "o2", day = 10, units = 10, delivered = 3) // defect (30% shipped)
        r.state(V, today = 20) shouldBe VendorRelationships.Trust.PROBATION
    }

    test("a redemption trade restores trust") {
        val r = rel()
        order(r, "o1", day = 0, units = 10, delivered = 10)
        order(r, "o2", day = 10, units = 10, delivered = 0) // defect → back to probation
        r.state(V, today = 20) shouldBe VendorRelationships.Trust.PROBATION
        order(r, "o3", day = 20, units = 10, delivered = 10) // redeem
        r.state(V, today = 30) shouldBe VendorRelationships.Trust.TRUSTED
    }

    test("a second defection on probation drops the vendor for good") {
        val r = rel()
        order(r, "o1", day = 0, units = 10, delivered = 10) // trusted
        order(r, "o2", day = 10, units = 10, delivered = 0) // strike 1 → probation
        order(r, "o3", day = 20, units = 10, delivered = 0) // strike 2 → dropped
        r.state(V, today = 30) shouldBe VendorRelationships.Trust.DROPPED
        // Absorbing: even a later good trade can't resurrect it.
        order(r, "o4", day = 30, units = 10, delivered = 10)
        r.state(V, today = 40) shouldBe VendorRelationships.Trust.DROPPED
    }

    test("a rug-pull vendor: trusted after good trades, dropped once it stops shipping") {
        val r = rel()
        order(r, "g1", day = 0, units = 10, delivered = 10)
        r.state(V, today = 6) shouldBe VendorRelationships.Trust.TRUSTED
        order(r, "b1", day = 6, units = 10, delivered = 0)  // rug pull begins
        r.state(V, today = 12) shouldBe VendorRelationships.Trust.PROBATION
        order(r, "b2", day = 12, units = 10, delivered = 0) // keeps failing → dropped
        r.state(V, today = 18) shouldBe VendorRelationships.Trust.DROPPED
    }
})
