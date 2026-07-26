package dev.supervend.llm

import dev.supervend.model.RoleId
import kotlinx.serialization.Serializable

/**
 * A staffing assignment recorded in the run manifest (software spec §11). One generic prompt
 * suite fills every slot — no per-model routing sophistication (CLAUDE.md #9, PRD R44). The
 * [fallback] model is used only on endpoint disappearance, and the switch is a manifest event.
 */
@Serializable
data class ModelSlot(
    val role: RoleId,
    val modelId: String,
    val fallbackModelId: String = "openrouter/auto",
)

/** The set of slots for a run; serialized whole into `run_manifest.model_slots_json`. */
@Serializable
data class ModelSlots(val slots: List<ModelSlot>)
