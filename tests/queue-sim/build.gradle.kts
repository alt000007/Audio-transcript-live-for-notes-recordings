// Runs the app's real queue code (Jobs, JobRunner, Chunker, GroqEngine) on a
// desktop JVM with simulated engines, files and network. No phone needed.
//
//   cd tests/queue-sim && gradle run
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }


sourceSets {
    main {
        kotlin {
            srcDir("../../android/app/src/main/java")
            srcDir(".")
            include(
                "app/scribe/Jobs.kt", "app/scribe/JobRunner.kt", "app/scribe/Chunker.kt",
                "app/scribe/GroqEngine.kt", "app/scribe/Diag.kt", "Sim.kt", "Stubs.kt",
            )
        }
    }
}

dependencies {
    // Android's own org.json, so the code runs exactly as it does on the phone.
    implementation("org.robolectric:android-all:15-robolectric-12650502")
}

application { mainClass.set("SimKt") }
