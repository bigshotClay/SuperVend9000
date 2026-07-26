package dev.supervend.personas

import dev.supervend.model.ActionType
import dev.supervend.plan.Roles
import dev.supervend.plan.Workplan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PersonaRegistryTest : FunSpec({

    val reg = PersonaRegistry.DEFAULT

    test("registry covers exactly the roles the plan may name") {
        reg.roles shouldBe Roles.all
    }

    test("every plan roleId resolves to a persona (personas §9)") {
        for (step in Workplan.PLAN.allSteps) {
            reg.get(step.roleId.value) ?: error("unresolved role ${step.roleId.value} in ${step.id.value}")
        }
    }

    test("every step mandate ⊆ its persona mandate (dispatch binds persona tools)") {
        for (step in Workplan.PLAN.allSteps) {
            val persona = reg.require(step.roleId.value)
            (persona.mandate.allowed).shouldContainAll(step.mandate.allowed)
        }
    }

    test("every mandate ⊆ implemented ActionTypes") {
        val implemented = ActionType.entries.toSet()
        reg.personas.forEach { it.mandate.allowed.shouldContainAll(emptySet()); (it.mandate.allowed - implemented) shouldBe emptySet() }
    }

    test("nine seats are model-staffed; the rest are scripts (LLM headcount 9 of 19)") {
        val modelRoles = reg.personas.filter { it.staffing is Staffing.Model }.map { it.role.value }.toSet()
        modelRoles shouldBe Roles.modelSeats
    }

    test("every model seat's slot exists (non-empty SlotId)") {
        reg.personas.filter { it.staffing is Staffing.Model }.forEach {
            val slot = (it.staffing as Staffing.Model).slot
            (slot.value.isNotBlank()) shouldBe true
        }
    }

    test("creeds are within the ≤150-token budget") {
        reg.personas.forEach { it.creed.approxTokens() shouldBeLessThanOrEqual 150 }
    }

    test("rule-4 injection hygiene is present on every external-text seat (personas §3)") {
        reg.personas.filter { it.touchesExternalText }.forEach { p ->
            (Creed.INJECTION_HYGIENE in p.creed.rules) shouldBe true
        }
        // And the three known external-text seats are exactly the ones flagged.
        reg.personas.filter { it.touchesExternalText }.map { it.role.value }.toSet() shouldBe
            setOf(Roles.QUOTE_EXTRACTOR, Roles.REPLY_CLASSIFIER, Roles.MAIL_SORTER)
    }

    test("registry is a content-addressed, round-tripping JSON artifact") {
        reg.hash() shouldBe PersonaRegistry.DEFAULT.hash()
        val json = reg.canonicalJson()
        val decoded = Json.decodeFromString(ListSerializer(Persona.serializer()), json)
        decoded.size shouldBe reg.personas.size
    }
})
