rootProject.name = "vending-harness"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Modules are added stage-by-stage per the Master Workplan build order.
// Stage 1: core/model + core/state
include(":core:model")
include(":core:state")

// Stage 2: sim/ scripted mode
include(":sim")

// Stage 3: core/llm + harness qualify + harness baseline (M2)
include(":core:llm")

// Stage 4: plan + gates + validate + personas
include(":core:plan")
include(":core:gates")
include(":core:validate")
include(":personas")

// Stage 5: orchestrator + recovery (M3)
include(":core:recovery")
include(":core:orchestrator")

// Stage 6: procure + pricing + audit
include(":core:procure")
include(":core:pricing")
include(":core:audit")

// Stage 7: experiment matrix reporting
include(":reports")

include(":app")
