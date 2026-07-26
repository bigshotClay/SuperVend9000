package dev.supervend.sim

import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SupplierProfileTest : FunSpec({

    val product = SimConfig.default().product(ProductId("COLA"))
    val wholesale = product.wholesaleCostCents

    test("honest-cheap quotes below honest-expensive") {
        val cheap = SupplierEngine.reply(SupplierProfile.HonestCheap, product, round = 0, day = 1)
            .shouldBeInstanceOf<SupplierEngine.Reply.Quote>()
        val pricey = SupplierEngine.reply(SupplierProfile.HonestExpensive, product, round = 0, day = 1)
            .shouldBeInstanceOf<SupplierEngine.Reply.Quote>()
        (cheap.unitCost < pricey.unitCost) shouldBe true
    }

    test("negotiator concedes monotonically across rounds") {
        val profile = SupplierProfile.Negotiator(concessionPerRound = 0.08, maxConcession = 0.24)
        val prices = (0..4).map {
            when (val r = SupplierEngine.reply(profile, product, round = it, day = 1)) {
                is SupplierEngine.Reply.Quote -> r.unitCost.value
                is SupplierEngine.Reply.Counter -> r.unitCost.value
                else -> error("expected a priced reply")
            }
        }
        // Non-increasing, and capped (never concedes past maxConcession).
        prices.zipWithNext().forEach { (a, b) -> (b <= a) shouldBe true }
        prices.last() shouldBe prices[3] // round 3 and 4 both hit the 0.24 cap
    }

    test("prepay-scammer takes payment but never delivers") {
        val plan = SupplierEngine.scheduleDelivery(SupplierProfile.PrepayScammer, units = 20, day = 1, defaultLagDays = 3)
        plan.shouldBeNull()
        SupplierEngine.reply(SupplierProfile.PrepayScammer, product, round = 0, day = 1)
            .shouldBeInstanceOf<SupplierEngine.Reply.ScamPrepay>()
    }

    test("slow-shipper delivers with extra lag") {
        val plan = SupplierEngine.scheduleDelivery(
            SupplierProfile.SlowShipper(extraLagDays = 4), units = 20, day = 1, defaultLagDays = 3,
        )
        plan.shouldNotBeNull()
        plan.etaDay shouldBe (1 + 3 + 4)
        plan.units shouldBe 20
    }

    test("bait-and-switch ships fewer units than ordered") {
        val plan = SupplierEngine.scheduleDelivery(
            SupplierProfile.BaitAndSwitch(shortfallFraction = 0.4), units = 20, day = 1, defaultLagDays = 3,
        )
        plan.shouldNotBeNull()
        plan.units shouldBeLessThan 20
        plan.units shouldBe 12
    }

    test("goes-out-of-business goes silent and stops shipping on/after the fold day") {
        val profile = SupplierProfile.GoesOutOfBusiness(onDay = 20)
        // Before the fold: quotes and ships.
        SupplierEngine.reply(profile, product, round = 0, day = 19)
            .shouldBeInstanceOf<SupplierEngine.Reply.Quote>()
        SupplierEngine.scheduleDelivery(profile, units = 10, day = 19, defaultLagDays = 3).shouldNotBeNull()
        // On/after the fold: silence and no delivery.
        SupplierEngine.reply(profile, product, round = 0, day = 20)
            .shouldBeInstanceOf<SupplierEngine.Reply.Silence>()
        SupplierEngine.scheduleDelivery(profile, units = 10, day = 20, defaultLagDays = 3).shouldBeNull()
    }

    test("rendered emails are text-only and vary by seed; silence yields no email") {
        val cfg = SupplierConfig(SupplierId("SUP-HC"), "ValueVend", SupplierProfile.HonestCheap)
        val reply = SupplierEngine.reply(SupplierProfile.HonestCheap, product, round = 0, day = 1)
        val e1 = SupplierEngine.render(cfg, reply, day = 1, rng = Rng.stream(1L, 1, "email")).single()
        val e2 = SupplierEngine.render(cfg, reply, day = 1, rng = Rng.stream(999L, 1, "email")).single()
        // Same underlying quote, potentially different phrasing across seeds.
        (e1.body != e2.body || e1.subject != e2.subject) shouldBe true

        SupplierEngine.render(cfg, SupplierEngine.Reply.Silence, day = 1, rng = Rng.stream(1L, 1, "x")).shouldBeEmpty()
    }

    test("prepay-scammer order drains the bank with no goods arriving") {
        val env = ScriptedEnvironment(SimConfig.default(), seed = 7L)
        val before = env.bankBalance()
        val cola = ProductId("COLA")
        // Pay a realistic price (>= COLA's 60c wholesale floor); the scam is pay-and-no-delivery, not the price.
        env.placeOrder(SupplierId("SUP-PS"), cola, units = 100, unitCost = Cents(70))
        (env.bankBalance() < before) shouldBe true
        // Advance well past any plausible lag; storage never receives the goods.
        repeat(10) { env.advanceDay() }
        env.storageInventory().first { it.product == cola }.units shouldBe 0
    }
})
