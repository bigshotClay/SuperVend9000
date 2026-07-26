package dev.supervend.app

import dev.supervend.llm.qualify.Battery
import dev.supervend.llm.qualify.BatteryModels
import dev.supervend.state.RunDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class QualificationStoreTest : FunSpec({

    test("a qualification report persists to the run DB and is qualified for a perfect model") {
        RunDatabase.inMemory().use { db ->
            val battery = Battery.default
            val report = battery.run(BatteryModels.perfect(battery), "mock-perfect")
            QualificationStore.save(db.connection, report)

            db.connection.createStatement().executeQuery(
                "SELECT model_id, passed, total, qualified FROM qualification",
            ).use { rs ->
                rs.next() shouldBe true
                rs.getString("model_id") shouldBe "mock-perfect"
                rs.getInt("passed") shouldBe report.total
                rs.getInt("qualified") shouldBe 1
            }
        }
    }
})
