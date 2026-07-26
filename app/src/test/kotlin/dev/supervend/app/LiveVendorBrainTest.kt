package dev.supervend.app

import dev.supervend.app.harness.FullRunner
import dev.supervend.app.harness.LiveVendorBrain
import dev.supervend.llm.MockModel
import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import dev.supervend.sim.ProductConfig
import dev.supervend.sim.SimConfig
import dev.supervend.sim.SupplierEngine
import dev.supervend.sim.SupplierMode
import dev.supervend.sim.VendorPersona
import dev.supervend.sim.VendorReplyRequest
import dev.supervend.state.RunDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * Live-vendor engine (LIVE-SUPPLIERS-SPEC), zero-network. A [MockModel] stands in for the qwen
 * vendor, so the whole model-vs-model loop is exercised bit-reproducibly: the brain maps typed
 * intents to the existing [SupplierEngine.Reply], the sim clamps the floor, competence drives
 * delivery from the seed, and a full [FullRunner] run stays deterministic and gated.
 */
class LiveVendorBrainTest : FunSpec({

    val cola = ProductConfig(
        id = ProductId("COLA"), wholesaleCostCents = Cents(60), referencePriceCents = Cents(150),
        baseDailyDemand = 30.0, elasticity = 1.3, weekdayMultipliers = List(7) { 1.0 },
        demandNoiseFraction = 0.1, machineCapacity = 40,
    )

    fun brain(json: String) = LiveVendorBrain(MockModel.constant(json), "mock", buyerMarginFloorBps = 2500)

    fun req(round: Int, persona: VendorPersona) = VendorReplyRequest(
        supplierId = SupplierId("LV-1"), supplierName = "Meridian", persona = persona, product = cola,
        round = round, day = 1, rfqBody = "What's your price for COLA?", dependence = 0.0, history = emptyList(),
    )

    test("intent maps to the right reply type and round decides quote vs counter") {
        val p = VendorPersona(floorMult = 1.10, targetMargin = 0.5, competence = 0.8)
        val b = brain("""{"intent":"COUNTER","unitCents":120,"message":"we can do 1.20"}""")
        b.reply(req(round = 0, persona = p)).shouldBe(SupplierEngine.Reply.Quote(Cents(120)))   // round 0 → quote
        b.reply(req(round = 2, persona = p)).shouldBe(SupplierEngine.Reply.Counter(Cents(120)))  // later → counter
        brain("""{"intent":"DECLINE","unitCents":0,"message":"no"}""").reply(req(1, p)) shouldBe SupplierEngine.Reply.Refusal
    }

    test("a quote below the vendor's hard floor is clamped up to the floor") {
        val p = VendorPersona(floorMult = 1.15, targetMargin = 0.5, competence = 0.8)
        val floor = SupplierEngine.floorPrice(cola, 1.15) // 60 * 1.15 = 69
        val reply = brain("""{"intent":"QUOTE","unitCents":10,"message":"cheap"}""").reply(req(0, p))
        reply shouldBe SupplierEngine.Reply.Quote(floor)
    }

    test("unusable model output falls back to the deterministic scripted persona (never wedges)") {
        val p = VendorPersona(floorMult = 1.10, targetMargin = 0.5, competence = 0.8)
        val reply = LiveVendorBrain(MockModel.constant("not json at all"), "mock", 2500).reply(req(0, p))
        // The fallback still produces a valid priced reply at or above the floor.
        val price = (reply as SupplierEngine.Reply.Quote).unitCost.value
        (price >= SupplierEngine.floorPrice(cola, 1.10).value).shouldBeTrue()
    }

    test("a full live-mode run is deterministic and stays gated (zero network)") {
        val config = SimConfig.liveMode()
        config.supplierMode shouldBe SupplierMode.LIVE
        val vendorJson = """{"intent":"QUOTE","unitCents":70,"message":"our best price"}"""

        fun runOnce(): List<Long> = RunDatabase.inMemory().use { db ->
            val runner = FullRunner(
                MockModel.constant("{}"), "mock", config = config,
                vendorBrain = brain(vendorJson),
            )
            runBlocking { runner.run("live-1", seed = 7, days = 30, db.connection) }.map { it.netWorthAfter.value }
        }

        val a = runOnce()
        val b = runOnce()
        a shouldBe b                          // bit-reproducible across runs
        (a.size == 30).shouldBeTrue()         // completed the horizon
    }
})
