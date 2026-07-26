package dev.supervend.sim

import kotlinx.serialization.Serializable

/** Which engine produces vendor replies (LIVE-SUPPLIERS-SPEC §3). Hashed into the run manifest. */
@Serializable
enum class SupplierMode {
    /** Deterministic scripted profiles — the bit-reproducible experimental control. */
    SCRIPTED,

    /** Model-generated negotiation via an injected [VendorBrain]; delivery driven by seeded competence. */
    LIVE,
}
