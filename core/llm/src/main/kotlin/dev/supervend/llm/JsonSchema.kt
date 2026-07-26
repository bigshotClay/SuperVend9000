package dev.supervend.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Generates a JSON Schema from a `@Serializable` type's [SerialDescriptor] (CLAUDE.md #7: the
 * agent-facing schema is a *projection* of the Kotlin type, never a hand-written document that
 * can drift). The walker covers the constructs the harness's agent-facing types use: primitives,
 * inline value classes, enums, lists, maps, data classes, and sealed hierarchies (rendered as
 * `oneOf` with a `type` discriminator matching kotlinx's default polymorphic encoding).
 *
 * Pure: descriptor in, schema element out. No I/O, no reflection beyond the descriptor.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchema {

    private val pretty = Json { prettyPrint = true }

    inline fun <reified T> of(): String = of(serializer<T>())

    fun of(serializer: KSerializer<*>): String =
        pretty.encodeToString(JsonElement.serializer(), element(serializer.descriptor))

    fun elementOf(serializer: KSerializer<*>): JsonElement = element(serializer.descriptor)

    private fun element(descriptor: SerialDescriptor): JsonElement = when (descriptor.kind) {
        is PrimitiveKind -> primitive(descriptor.kind as PrimitiveKind)
        SerialKind.ENUM -> buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                for (i in 0 until descriptor.elementsCount) add(JsonPrimitive(descriptor.getElementName(i)))
            })
        }
        StructureKind.LIST -> buildJsonObject {
            put("type", "array")
            put("items", element(descriptor.getElementDescriptor(0)))
        }
        StructureKind.MAP -> buildJsonObject {
            put("type", "object")
            put("additionalProperties", element(descriptor.getElementDescriptor(1)))
        }
        StructureKind.CLASS, StructureKind.OBJECT -> classObject(descriptor)
        is PolymorphicKind, SerialKind.CONTEXTUAL -> sealedOneOf(descriptor)
    }

    private fun primitive(kind: PrimitiveKind): JsonObject = buildJsonObject {
        val type = when (kind) {
            PrimitiveKind.BOOLEAN -> "boolean"
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> "string"
        }
        put("type", type)
    }

    private fun classObject(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            for (i in 0 until descriptor.elementsCount) {
                put(descriptor.getElementName(i), element(descriptor.getElementDescriptor(i)))
            }
        })
        val required = (0 until descriptor.elementsCount).filterNot { descriptor.isElementOptional(it) }
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(descriptor.getElementName(it))) } })
        }
        put("additionalProperties", false)
    }

    /**
     * Sealed hierarchies serialize (kotlinx default) as an object carrying a `type`
     * discriminator plus the subtype's fields. The descriptor exposes subtypes as the element
     * descriptors of its second child (`value`); we render each as a `oneOf` branch.
     */
    private fun sealedOneOf(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        val subtypes = if (descriptor.elementsCount >= 2) {
            val valueDesc = descriptor.getElementDescriptor(1)
            (0 until valueDesc.elementsCount).map { valueDesc.getElementDescriptor(it) }
        } else {
            emptyList()
        }
        put("oneOf", buildJsonArray {
            for (sub in subtypes) {
                add(buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("const", sub.serialName)
                        })
                        for (i in 0 until sub.elementsCount) {
                            put(sub.getElementName(i), element(sub.getElementDescriptor(i)))
                        }
                    })
                })
            }
        })
    }
}
