package dev.supervend.llm

import dev.supervend.model.SupplierReplyClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class Sample(val name: String, val qty: Int, val tags: List<String>, val ok: Boolean)

@Serializable
private enum class Color { RED, GREEN, BLUE }

class JsonSchemaTest : FunSpec({

    test("data class projects to an object schema with typed properties and required fields") {
        val schema = Json.parseToJsonElement(JsonSchema.of<Sample>()).jsonObject
        schema["type"]!!.jsonPrimitive.content shouldBe "object"
        val props = schema["properties"]!!.jsonObject
        props["name"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "string"
        props["qty"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "integer"
        props["ok"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "boolean"
        props["tags"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "array"
        props["tags"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "string"
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        required shouldBe setOf("name", "qty", "tags", "ok")
    }

    test("enum projects to a string with an enum list") {
        val schema = Json.parseToJsonElement(JsonSchema.of<Color>()).jsonObject
        schema["type"]!!.jsonPrimitive.content shouldBe "string"
        schema["enum"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("RED", "GREEN", "BLUE")
    }

    test("sealed hierarchy projects to oneOf with a discriminating type const") {
        // SupplierReplyClass is the real agent-facing sealed type (software spec §3).
        val schema = JsonSchema.of(SupplierReplyClass.serializer())
        schema shouldContain "oneOf"
        // Every subtype's serialName appears as a const discriminator.
        schema shouldContain "Ambiguous"
        schema shouldContain "\"const\""
    }
})
