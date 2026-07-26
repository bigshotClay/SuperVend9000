package dev.supervend.validate

import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import dev.supervend.model.ValidationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** State in which the fixtures' ids resolve — so referential failures are isolated from business ones. */
private val FIXTURE_STATE = object : ValidationState {
    override fun productExists(product: ProductId) = product.value in setOf("COLA", "WATER", "CHIPS")
    override fun supplierExists(supplier: SupplierId) = supplier.value.startsWith("SUP-")
}

private fun load(artifact: String, kind: String): String =
    FixtureMatrixTest::class.java.classLoader
        .getResourceAsStream("fixtures/$artifact/$kind.json")!!
        .readBytes().decodeToString()

class FixtureMatrixTest : FunSpec({

    // (validator, artifact-dir) pairs — the substantive seats ship the full good/malformed/rule triad.
    val cases = listOf(
        AssortmentPlanValidator() to "AssortmentPlan",
        PurchaseOrderValidator() to "PurchaseOrder",
        QuoteValidator() to "Quote",
        AmendmentProposalValidator() to "AmendmentProposal",
    )

    for ((validator, artifact) in cases) {
        test("$artifact: good fixture passes") {
            validator.validate(load(artifact, "good"), FIXTURE_STATE).shouldBeInstanceOf<ValidationResult.Pass>()
        }
        test("$artifact: malformed fixture fails at the schema layer") {
            val r = validator.validate(load(artifact, "malformed"), FIXTURE_STATE)
            val fail = r.shouldBeInstanceOf<ValidationResult.Fail>()
            // A malformed artifact never deserializes → the schema check is the failing one.
            fail.report.checks.any { it.id == "schema" && !it.passed } shouldBe true
        }
        test("$artifact: rule-violating fixture fails at the business layer") {
            val r = validator.validate(load(artifact, "rule-violating"), FIXTURE_STATE)
            val fail = r.shouldBeInstanceOf<ValidationResult.Fail>()
            // It deserialized fine (no schema failure) but a business assertion fired.
            fail.report.checks.none { it.id == "schema" && !it.passed } shouldBe true
            fail.report.checks.any { !it.passed } shouldBe true
        }
    }

    test("structural validator passes any JSON object and fails non-objects") {
        val v = StructuralValidator("MoveList")
        v.validate("""{"moves":[]}""", ValidationState.ANY).shouldBeInstanceOf<ValidationResult.Pass>()
        v.validate("""[1,2,3]""", ValidationState.ANY).shouldBeInstanceOf<ValidationResult.Fail>()
    }

    test("registry covers every artifact schema id the plan names") {
        ValidatorRegistry.DEFAULT.ids shouldBe dev.supervend.plan.Artifacts.all
    }
})
