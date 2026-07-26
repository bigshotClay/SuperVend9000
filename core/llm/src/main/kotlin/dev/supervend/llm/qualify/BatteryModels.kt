package dev.supervend.llm.qualify

import dev.supervend.llm.MockModel
import dev.supervend.llm.ModelPort

/**
 * Deterministic, zero-network stand-ins used to exercise the qualification pipeline in CI (the
 * threshold logic, persistence, and reporting) without any model access. These are test doubles
 * for the *harness*, not accuracy proxies for real models.
 */
object BatteryModels {

    /** Answers every case correctly → qualifies. Verifies the >90% pass path end-to-end. */
    fun perfect(battery: Battery): ModelPort {
        val table = battery.cases.associate { it.pkg.render() to it.canonicalAnswer }
        return MockModel.scripted(table)
    }

    /**
     * Answers exactly the first [correct] cases (by battery order) correctly and flubs the rest,
     * so tests can pin the report score precisely and check the threshold boundary.
     */
    fun partial(battery: Battery, correct: Int): ModelPort {
        val table = battery.cases.mapIndexed { i, c ->
            c.pkg.render() to if (i < correct) c.canonicalAnswer else "I cannot help with that."
        }.toMap()
        return MockModel.scripted(table)
    }
}
