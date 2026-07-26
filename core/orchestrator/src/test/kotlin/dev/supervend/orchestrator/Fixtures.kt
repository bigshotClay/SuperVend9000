package dev.supervend.orchestrator

import dev.supervend.gates.GateConfig
import dev.supervend.gates.GateRegistry
import dev.supervend.model.RunId
import dev.supervend.plan.Workplan
import dev.supervend.recovery.RecoveryClassifier
import dev.supervend.recovery.RecoveryDecision
import dev.supervend.recovery.RecoveryPipeline
import dev.supervend.validate.ValidatorRegistry
import java.io.File

/** Standard, fully-wired orchestrator over the real registries; only the classifier varies per test. */
internal fun orchestrator(
    classifier: RecoveryClassifier = RecoveryClassifier { _, _ -> error("recovery not expected in this run") },
    stall: StallDetector = StallDetector(),
    recoveryCap: Int = 2,
): Orchestrator = Orchestrator(
    runId = RunId("golden"),
    plan = Workplan.PLAN,
    gates = GateRegistry(GateConfig.default()),
    validators = ValidatorRegistry.DEFAULT,
    recovery = RecoveryPipeline(classifier, cap = recoveryCap),
    stall = stall,
)

/**
 * Golden-file check. With `-Dgolden.write=true` (and `-Dgolden.dir`) it (re)writes the committed
 * file; otherwise it asserts the run's output matches the committed baseline byte-for-byte. Any
 * drift in the deterministic core is a test failure, which is the whole point.
 */
internal fun assertGolden(name: String, content: String) {
    if (System.getProperty("golden.write") == "true") {
        val dir = System.getProperty("golden.dir") ?: error("golden.dir not set")
        File(dir).mkdirs()
        File(dir, name).writeText(content)
        return
    }
    val resource = object {}.javaClass.getResource("/golden/$name")
        ?: error("missing committed golden /golden/$name — regenerate with -Dgolden.write=true")
    val expected = resource.readText()
    if (expected != content) {
        error("golden drift in $name:\nexpected:\n$expected\n\nactual:\n$content")
    }
}
