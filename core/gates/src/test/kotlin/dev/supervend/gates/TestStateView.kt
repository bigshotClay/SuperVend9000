package dev.supervend.gates

import dev.supervend.model.Cents
import dev.supervend.model.ProductId
import dev.supervend.model.SanityBand
import dev.supervend.model.StateView
import dev.supervend.model.SupplierId

/** A plain immutable value holder standing in for a state snapshot in gate property tests. */
data class TestStateView(
    val costBases: Map<ProductId, Cents> = emptyMap(),
    val validatedSuppliers: Set<SupplierId> = emptySet(),
    val specs: Set<Pair<SupplierId, ProductId>> = emptySet(),
    val spentToday: Cents = Cents.ZERO,
    val bands: Map<ProductId, SanityBand> = emptyMap(),
) : StateView {
    override fun costBasis(product: ProductId): Cents = costBases[product] ?: Cents.ZERO
    override fun supplierValidated(supplier: SupplierId): Boolean = supplier in validatedSuppliers
    override fun specOnFile(supplier: SupplierId, product: ProductId): Boolean = (supplier to product) in specs
    override fun spendCommittedToday(): Cents = spentToday
    override fun sanityBand(product: ProductId): SanityBand =
        bands[product] ?: SanityBand(Cents.ZERO, Cents(Long.MAX_VALUE))
}
