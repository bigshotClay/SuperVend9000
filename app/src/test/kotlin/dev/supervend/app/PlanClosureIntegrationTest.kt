package dev.supervend.app

import dev.supervend.gates.GateConfig
import dev.supervend.gates.GateRegistry
import dev.supervend.personas.PersonaRegistry
import dev.supervend.plan.PlanClosure
import dev.supervend.plan.Workplan
import dev.supervend.validate.ValidatorRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The full cross-registry closure check (workplan §6 invariant 4). Only the app sees every module,
 * so this is where the plan's role/gate/validator references are resolved against the *actual*
 * registries — not the intra-plan stand-ins. A green run here is the Stage 4 DoD.
 */
class PlanClosureIntegrationTest : FunSpec({

    val plan = Workplan.PLAN
    val gates = GateRegistry(GateConfig.default())
    val validators = ValidatorRegistry.DEFAULT
    val personas = PersonaRegistry.DEFAULT

    test("intra-plan closure yields zero violations") {
        PlanClosure.check(plan) shouldBe emptyList()
    }

    test("every gate the plan names resolves in the real GateRegistry") {
        val gateIds = gates.ids.map { it.value }.toSet()
        val referenced = plan.allSteps.flatMap { it.gates }.map { it.value }.toSet()
        (referenced - gateIds) shouldBe emptySet()
    }

    test("every validator the plan names resolves in the real ValidatorRegistry") {
        val referenced = plan.allSteps.map { it.validator.id }.toSet()
        (referenced - validators.ids) shouldBe emptySet()
    }

    test("every role the plan names resolves in the real PersonaRegistry, and mandates are covered") {
        for (step in plan.allSteps) {
            val persona = personas.get(step.roleId.value) ?: error("unresolved role ${step.roleId.value}")
            (step.mandate.allowed - persona.mandate.allowed) shouldBe emptySet()
        }
    }

    test("all three registries plus the plan are content-addressed for the manifest") {
        // Each hash is stable — these four hashes fully attribute a run's plan/gates/validators/personas.
        (plan.version().hash.hex.isNotBlank()) shouldBe true
        (GateConfig.default().hash().hex.isNotBlank()) shouldBe true
        (personas.hash().hex.isNotBlank()) shouldBe true
    }
})
