package dev.supervend.validate

import dev.supervend.model.SemVer
import dev.supervend.model.ValidationResult
import dev.supervend.model.ValidatorRef
import dev.supervend.plan.Artifacts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val JSON = Json { ignoreUnknownKeys = false; isLenient = false }
private val V1 = SemVer(1, 0, 0)

/** Deserialize-or-schema-fail helper: turns a kotlinx exception into a clean schema-layer failure. */
private inline fun <T> decode(json: String, decoder: (String) -> T, body: (T) -> ValidationResult): ValidationResult =
    try {
        body(decoder(json))
    } catch (e: Exception) {
        Checks.schemaFail(e.message ?: e::class.simpleName ?: "deserialization failed")
    }

/** BOOT-01 AssortmentPlan: non-empty, par ≥ reorder ≥ 0, products resolve. */
class AssortmentPlanValidator : ArtifactValidator {
    override val ref = ValidatorRef(Artifacts.ASSORTMENT_PLAN, V1)
    override fun validate(json: String, state: ValidationState): ValidationResult =
        decode(json, { JSON.decodeFromString(AssortmentPlan.serializer(), it) }) { plan ->
            val c = Checks()
            c.assert("non-empty", plan.lines.isNotEmpty(), "≥1 assortment line", "${plan.lines.size} lines")
            plan.lines.forEachIndexed { i, l ->
                c.assert("product-exists[$i]", state.productExists(l.product), "known product", l.product.value)
                c.assert("par≥reorder[$i]", l.parLevel >= l.reorderPoint, "parLevel ≥ reorderPoint", "${l.parLevel} < ${l.reorderPoint}")
                c.assert("reorder≥0[$i]", l.reorderPoint >= 0, "reorderPoint ≥ 0", "${l.reorderPoint}")
            }
            c.toResult()
        }
}

/** BOOT-06/PRO-03 PurchaseOrder: units>0, cost>0, supplier resolves, spec present (spec-before-pay). */
class PurchaseOrderValidator : ArtifactValidator {
    override val ref = ValidatorRef(Artifacts.PURCHASE_ORDER, V1)
    override fun validate(json: String, state: ValidationState): ValidationResult =
        decode(json, { JSON.decodeFromString(PurchaseOrderArtifact.serializer(), it) }) { po ->
            val c = Checks()
            c.assert("supplier-exists", state.supplierExists(po.supplier), "known supplier", po.supplier.value)
            c.assert("product-exists", state.productExists(po.product), "known product", po.product.value)
            c.assert("units>0", po.units > 0, "units > 0", "${po.units}")
            c.assert("unitCost>0", po.unitCostCents > 0, "unitCostCents > 0", "${po.unitCostCents}")
            c.assert("spec-present", po.specArtifact.isNotBlank(), "non-empty specArtifact", "blank")
            c.toResult()
        }
}

/** COM-02 Quote: units>0, cost>0, supplier/product resolve. */
class QuoteValidator : ArtifactValidator {
    override val ref = ValidatorRef(Artifacts.QUOTE, V1)
    override fun validate(json: String, state: ValidationState): ValidationResult =
        decode(json, { JSON.decodeFromString(QuoteArtifact.serializer(), it) }) { q ->
            val c = Checks()
            c.assert("supplier-exists", state.supplierExists(q.supplier), "known supplier", q.supplier.value)
            c.assert("product-exists", state.productExists(q.product), "known product", q.product.value)
            c.assert("units>0", q.units > 0, "units > 0", "${q.units}")
            c.assert("unitCost>0", q.unitCostCents > 0, "unitCostCents > 0", "${q.unitCostCents}")
            c.toResult()
        }
}

/** AUD-02 AmendmentProposal: sealed set only (free with schema) + per-type bounds (business). */
class AmendmentProposalValidator : ArtifactValidator {
    override val ref = ValidatorRef(Artifacts.AMENDMENT_PROPOSAL, V1)
    override fun validate(json: String, state: ValidationState): ValidationResult =
        decode(json, { JSON.decodeFromString(AmendmentProposal.serializer(), it) }) { proposal ->
            val c = Checks()
            proposal.amendments.forEachIndexed { i, a ->
                when (a) {
                    is Amendment.PriceBandAdjust ->
                        c.assert("band-delta-in-bounds[$i]", kotlin.math.abs(a.deltaBps) <= MAX_BAND_DELTA_BPS,
                            "|deltaBps| ≤ $MAX_BAND_DELTA_BPS", "${a.deltaBps}")
                    is Amendment.ProductMixChange ->
                        c.assert("mix-product-exists[$i]", state.productExists(a.product), "known product", a.product.value)
                    is Amendment.SupplierListChange -> Unit // add/remove is unconstrained at this layer
                }
            }
            c.toResult()
        }

    private companion object {
        const val MAX_BAND_DELTA_BPS = 2_000L // ±20% per amendment (bounded set; Stage 6 refines)
    }
}

/**
 * Schema-only validator for mechanical script outputs (personas §2: a Script staffing "cannot fail
 * validation in a well-tested build"). Confirms the artifact is a well-formed JSON object of the
 * declared schema id — the referential/business layers are the producing script's own invariants.
 */
class StructuralValidator(private val schemaId: String) : ArtifactValidator {
    override val ref = ValidatorRef(schemaId, V1)
    override fun validate(json: String, state: ValidationState): ValidationResult =
        decode(json, { JSON.parseToJsonElement(it) }) { el ->
            val c = Checks()
            c.assert("json-object", el is JsonObject, "a JSON object", el::class.simpleName ?: "non-object")
            c.toResult()
        }
}
