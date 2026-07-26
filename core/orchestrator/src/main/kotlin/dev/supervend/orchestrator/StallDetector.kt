package dev.supervend.orchestrator

/**
 * Loop/stall detection (PRD R42, software spec §9). Two independent counters trip a hard reset of
 * the program counter with a fresh brief: N consecutive failed-or-no-op step outcomes, and M steps
 * elapsed without any economic (money-mutating) action landing. Both thresholds default to the
 * manifest-recorded values (N=3, M=15) and are the harness's answer to tool-use decay: a model
 * that spins without acting is forcibly re-grounded rather than left to drift.
 *
 * Pure counter logic — no clock, no randomness — so a replay reproduces every reset position.
 */
class StallDetector(val n: Int = 3, val m: Int = 15) {
    private var consecutive = 0
    private var sinceEconomic = 0

    /**
     * Record one step's outcome. [failed] is true for an incident-producing outcome (a failed
     * action); [economicApplied] is how many money-mutating actions the step actually applied. The
     * two counters are independent: consecutive failures (a step legitimately producing a
     * non-economic artifact is *not* a failure and resets it) and steps elapsed with no money moved.
     * Returns the trigger that fired (and resets both counters) or `null`.
     */
    fun record(failed: Boolean, economicApplied: Int): StallTrigger? {
        consecutive = if (failed) consecutive + 1 else 0
        sinceEconomic = if (economicApplied > 0) 0 else sinceEconomic + 1
        return when {
            consecutive >= n -> { reset(); StallTrigger.CONSECUTIVE_FAILURES }
            sinceEconomic >= m -> { reset(); StallTrigger.NO_ECONOMIC_ACTION }
            else -> null
        }
    }

    fun reset() {
        consecutive = 0
        sinceEconomic = 0
    }
}
