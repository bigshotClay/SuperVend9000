package dev.supervend.orchestrator

import dev.supervend.model.ArtifactRef
import dev.supervend.model.GateDecision
import dev.supervend.model.Hashing
import dev.supervend.model.IncidentId
import dev.supervend.model.PlanCounterSnapshot
import dev.supervend.model.PredicateResult
import dev.supervend.model.RunId
import dev.supervend.model.Severity
import dev.supervend.model.StepStatus
import dev.supervend.model.UnusableReason
import dev.supervend.model.ValidationResult
import dev.supervend.plan.Plan
import dev.supervend.plan.StepDef
import dev.supervend.recovery.FailureEvidence
import dev.supervend.recovery.IncidentRecord
import dev.supervend.recovery.RecoveryOutcome
import dev.supervend.recovery.RecoveryPipeline
import dev.supervend.validate.ValidatorRegistry

/**
 * The deterministic daily-loop driver (software spec §9). It sequences steps; it does not itself
 * reason. For each step it verifies preconditions, runs the (script or model) seat under a bounded
 * scrubbed retry, submits the resulting actions through the single [StateActor], and — on any
 * failure — assembles a structured [IncidentRecord] and routes it by severity. Stall detection
 * force-resets the counter when the run stops making economic progress. Every branch appends to the
 * [RunTrace], the byte-stable golden evidence of the run.
 *
 * The whole driver is model-agnostic and clock-free: hand it a deterministic [StepRunner] and
 * [StepWorld] and it reproduces the same trace every time (Stage 5 golden runs).
 */
class Orchestrator(
    private val runId: RunId,
    private val plan: Plan,
    private val gates: dev.supervend.gates.GateRegistry,
    private val validators: ValidatorRegistry,
    private val recovery: RecoveryPipeline,
    private val stall: StallDetector = StallDetector(),
) {
    private val trace = MutableTrace()
    private val recoveryInvocations = mutableMapOf<String, Int>()

    /** Day-0 bootstrap segment, run once. */
    fun runBootstrap(world: StepWorld, runner: StepRunner): RunTrace {
        runSegment(day = 0, steps = plan.bootstrap, world = world, runner = runner)
        return trace.snapshot()
    }

    /** One daily-loop day (Day ≥ 1). The weekly audit replaces the TASKS phase every 7th day. */
    fun runDay(day: Int, world: StepWorld, runner: StepRunner): RunTrace {
        runSegment(day = day, steps = stepsForDay(day), world = world, runner = runner)
        return trace.snapshot()
    }

    /** A full week of daily loops (Days [startDay, startDay+6]); the 7th is an audit day. */
    fun runWeek(startDay: Int, world: StepWorld, runner: StepRunner): RunTrace {
        for (d in startDay until startDay + 7) {
            runSegment(day = d, steps = stepsForDay(d), world = world, runner = runner)
        }
        return trace.snapshot()
    }

    private fun stepsForDay(day: Int): List<StepDef> {
        val auditDay = day % 7 == 0
        return if (auditDay) {
            plan.dailyLoop.filter { it.phase != dev.supervend.model.DayPhase.TASKS } + plan.weeklyAudit
        } else {
            plan.dailyLoop
        }
    }

    private fun runSegment(day: Int, steps: List<StepDef>, world: StepWorld, runner: StepRunner) {
        var haltAfterPhase: dev.supervend.model.DayPhase? = null
        for ((index, step) in steps.withIndex()) {
            // A BLOCKING escalation pauses the loop at the phase boundary (spec §9 severity routing).
            if (haltAfterPhase != null && step.phase != haltAfterPhase) return

            val status = executeStep(day, index, step, world, runner)
            trace.counter += PlanCounterSnapshot(runId, day, index, step.id, status)

            if (blockingEscalated(status, step)) {
                haltAfterPhase = step.phase
            }
        }
    }

    /** True when a BLOCKING step failed and recovery escalated — the loop must pause. */
    private fun blockingEscalated(status: StepStatus, step: StepDef): Boolean {
        if (step.severityOnExhaustion != Severity.BLOCKING) return false
        if (status == StepStatus.SUCCESS || status == StepStatus.SKIPPED) return false
        return trace.recovery.lastOrNull()?.outcome is RecoveryOutcome.Escalated
    }

    private fun executeStep(day: Int, index: Int, step: StepDef, world: StepWorld, runner: StepRunner): StepStatus {
        // Conditional steps fire only on a triggering task; otherwise skip (not a failure).
        if (step.kind.firesOnlyOnTaskFlag() && !world.taskAvailableFor(step)) {
            afterStep(day, index, failed = false, economicApplied = 0)
            return StepStatus.SKIPPED
        }

        val preconditions = PreconditionVerifier.verify(step.preconditions, world)
        if (!PreconditionVerifier.allHeld(preconditions)) {
            failIncident(day, index, step, world, preconditions, FailureEvidence.NoArtifact, emptyList())
            afterStep(day, index, failed = true, economicApplied = 0)
            return StepStatus.PRECONDITION_MISMATCH
        }

        // Bounded scrubbed retry (PRD R12): the failed artifact is never re-shown (AttemptContext).
        var attempt = 0
        var priorFailure: dev.supervend.model.FailureReport? = null
        while (true) {
            val ctx = priorFailure?.let { AttemptContext.Again(attempt, it) } ?: AttemptContext.First(attempt)
            when (val result = runner.run(step, day, ctx)) {
                is AttemptResult.Unusable -> {
                    if (attempt >= step.retryBudget) {
                        failIncident(day, index, step, world, preconditions, FailureEvidence.UnusableOutput(result.reason), emptyList())
                        afterStep(day, index, failed = true, economicApplied = 0)
                        return StepStatus.AGENT_UNUSABLE
                    }
                    attempt++
                    priorFailure = null // fresh context; nothing to scrub from
                }
                is AttemptResult.Produced -> {
                    val ref = ArtifactRef(step.id, day, attempt, Hashing.sha256(result.artifactJson))
                    when (val v = validate(step, result.artifactJson, world)) {
                        is ValidationResult.Pass -> {
                            val submit = StateActor(gates, world, trace.gate).submit(step, result.actions)
                            if (submit.reject != null) {
                                failIncident(day, index, step, world, preconditions, FailureEvidence.NoArtifact, listOf(submit.reject))
                                afterStep(day, index, failed = true, economicApplied = submit.economicApplied)
                                return StepStatus.GATE_REJECTED
                            }
                            afterStep(day, index, failed = false, economicApplied = submit.economicApplied)
                            return StepStatus.SUCCESS
                        }
                        is ValidationResult.Fail -> {
                            if (attempt >= step.retryBudget) {
                                failIncident(day, index, step, world, preconditions, FailureEvidence.ValidationFailure(ref, v.report), emptyList())
                                afterStep(day, index, failed = true, economicApplied = 0)
                                return StepStatus.VALIDATION_FAILED
                            }
                            attempt++
                            priorFailure = v.report
                        }
                    }
                }
            }
        }
    }

    private fun validate(step: StepDef, json: String, world: StepWorld): ValidationResult =
        validators.require(step.validator.id).validate(json, world)

    private fun afterStep(day: Int, index: Int, failed: Boolean, economicApplied: Int) {
        stall.record(failed, economicApplied)?.let { recordReset(day, index, it) }
    }

    private fun recordReset(day: Int, index: Int, trigger: StallTrigger) {
        trace.resets += StallReset(day, index, trigger, trace.resets.size + 1)
        trace.counter += PlanCounterSnapshot(runId, day, index, dev.supervend.model.StepId("RESET"), StepStatus.RESET)
    }

    /** Assemble the incident, log it, and route it by severity (spec §9 / §10). */
    private fun failIncident(
        day: Int,
        index: Int,
        step: StepDef,
        world: StepWorld,
        preconditions: List<PredicateResult>,
        evidence: FailureEvidence,
        rejects: List<GateDecision.Reject>,
    ) {
        val incidentId = IncidentId("INC-$day-${step.id.value}-$index")
        val severity = step.severityOnExhaustion
        val incident = IncidentRecord(
            id = incidentId,
            day = day,
            counter = PlanCounterSnapshot(runId, day, index, step.id, statusFor(evidence, rejects)),
            stepDef = step,
            preconditionResults = preconditions,
            failure = evidence,
            gateRejections = rejects,
            stateSnapshot = world.snapshotRef(),
            severity = severity,
        )
        trace.incidents += incident

        when (severity) {
            Severity.NON_BLOCKING -> world.enqueueTask(kind = "incident:${step.id.value}", severity = severity)
            Severity.BLOCKING -> routeBlocking(day, incident, world)
        }
    }

    /** BLOCKING: pause and invoke recovery synchronously against harness-computed candidates. */
    private fun routeBlocking(day: Int, incident: IncidentRecord, world: StepWorld) {
        val candidates = plan.allSteps
            .filter { PreconditionVerifier.allHeld(PreconditionVerifier.verify(it.preconditions, world)) }
            .map { it.id }
        val key = incident.stepDef.id.value
        val prior = recoveryInvocations.getOrDefault(key, 0)
        val outcome = recovery.handle(incident, candidates, prior)
        recoveryInvocations[key] = prior + 1
        trace.recovery += RecoveryLogEntry(day, incident.id.value, prior, outcome)
    }

    private fun statusFor(evidence: FailureEvidence, rejects: List<GateDecision.Reject>): StepStatus = when {
        rejects.isNotEmpty() -> StepStatus.GATE_REJECTED
        evidence is FailureEvidence.ValidationFailure -> StepStatus.VALIDATION_FAILED
        evidence is FailureEvidence.UnusableOutput -> StepStatus.AGENT_UNUSABLE
        else -> StepStatus.PRECONDITION_MISMATCH
    }

    private class MutableTrace {
        val counter = mutableListOf<PlanCounterSnapshot>()
        val gate = mutableListOf<GateLogEntry>()
        val incidents = mutableListOf<IncidentRecord>()
        val recovery = mutableListOf<RecoveryLogEntry>()
        val resets = mutableListOf<StallReset>()
        fun snapshot() = RunTrace(counter.toList(), gate.toList(), incidents.toList(), recovery.toList(), resets.toList())
    }
}

/** Reach the `firesOnlyOnTask` flag without leaking the sealed `StepKind` details into the driver. */
private fun dev.supervend.plan.StepKind.firesOnlyOnTaskFlag(): Boolean =
    this is dev.supervend.plan.StepKind.PersonaStep && this.firesOnlyOnTask
