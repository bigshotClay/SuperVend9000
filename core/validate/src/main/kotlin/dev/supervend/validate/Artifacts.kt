package dev.supervend.validate

import dev.supervend.model.ProductId
import dev.supervend.model.SupplierId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The typed, `@Serializable` artifact shapes the substantive validators deserialize into. Their
 * agent-facing JSON schemas are generated from these types (CLAUDE.md #7). Money stays in integer
 * cents fields (CLAUDE.md #2). Only the artifacts with real referential/business rules are typed
 * here; mechanical script outputs use the structural (schema-only) validator.
 */

@Serializable
data class AssortmentPlan(val lines: List<AssortmentLine>)

@Serializable
data class AssortmentLine(val product: ProductId, val parLevel: Int, val reorderPoint: Int)

@Serializable
data class PurchaseOrderArtifact(
    val supplier: SupplierId,
    val product: ProductId,
    val units: Int,
    val unitCostCents: Long,
    val specArtifact: String,
)

@Serializable
data class QuoteArtifact(
    val supplier: SupplierId,
    val product: ProductId,
    val unitCostCents: Long,
    val units: Int,
)

@Serializable
data class AmendmentProposal(val amendments: List<Amendment>)

/** The sealed, bounded amendment set (workplan §4 AUD-02). */
@Serializable
sealed interface Amendment {
    @Serializable @SerialName("PriceBandAdjust") data class PriceBandAdjust(val product: ProductId, val deltaBps: Long) : Amendment
    @Serializable @SerialName("ProductMixChange") data class ProductMixChange(val product: ProductId, val addToAssortment: Boolean) : Amendment
    @Serializable @SerialName("SupplierListChange") data class SupplierListChange(val supplier: SupplierId, val add: Boolean) : Amendment
}
