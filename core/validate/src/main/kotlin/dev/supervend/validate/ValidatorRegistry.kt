package dev.supervend.validate

import dev.supervend.plan.Artifacts

/**
 * The validator registry (software spec §7). Covers every artifact schema id the plan names
 * (workplan §6 invariant 4): the substantive artifacts get purpose-built validators; mechanical
 * script outputs get a [StructuralValidator]. The closure test resolves every plan `ValidatorRef`
 * against this registry.
 */
class ValidatorRegistry(validators: List<ArtifactValidator>) {
    private val byId = validators.associateBy { it.ref.id }
    val ids: Set<String> get() = byId.keys
    fun get(id: String): ArtifactValidator? = byId[id]
    fun require(id: String): ArtifactValidator = byId[id] ?: error("no validator for $id")

    companion object {
        private val SUBSTANTIVE: List<ArtifactValidator> = listOf(
            AssortmentPlanValidator(),
            PurchaseOrderValidator(),
            QuoteValidator(),
            AmendmentProposalValidator(),
        )

        val DEFAULT: ValidatorRegistry by lazy {
            val covered = SUBSTANTIVE.map { it.ref.id }.toSet()
            val structural = (Artifacts.all - covered).map { StructuralValidator(it) }
            ValidatorRegistry(SUBSTANTIVE + structural)
        }
    }
}
