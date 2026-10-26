import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    application
}

group = "calc"
version = "1.0.0"
description = "Calculatrice fiable Java Swing"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Property-based testing engine for the JUnit Platform. 1.10.2 is built against JUnit Platform 1.14.4,
    // the one managed by the junit-bom above.
    testImplementation("net.jqwik:jqwik:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "calc.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()

    // Turns "no display" into a failure for the `ui` tests (used by CI) instead of skipping them:
    // `gradlew check -PrequireUiTests=true`, or the REQUIRE_UI_TESTS environment variable.
    // `-Dcalc.ui.required=true` is still accepted. Off by default: without a display they are skipped.
    val requireUiTests = providers.gradleProperty("requireUiTests")
        .orElse(providers.environmentVariable("REQUIRE_UI_TESTS"))
        .orElse(providers.systemProperty("calc.ui.required"))
        .orElse("false")
    systemProperty("calc.ui.required", requireUiTests.get())

    // `-Djava.awt.headless=true` given to Gradle only reaches the Gradle JVM, never the forked test JVM:
    // forward it explicitly so `gradlew check -Djava.awt.headless=true` really runs the tests without a display.
    System.getProperty("java.awt.headless")?.let { systemProperty("java.awt.headless", it) }

    // Runs a subset by JUnit tag (`ui`, `property`, `robustness`...): `gradlew test -PincludeTags=property`.
    // Several tags are separated by commas; `-PexcludeTags=` works the same way. See docs/TESTS.md.
    val includeTags = providers.gradleProperty("includeTags").orNull?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val excludeTags = providers.gradleProperty("excludeTags").orNull?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    if (includeTags.isNotEmpty() || excludeTags.isNotEmpty()) {
        useJUnitPlatform {
            if (includeTags.isNotEmpty()) includeTags(*includeTags.toTypedArray())
            if (excludeTags.isNotEmpty()) excludeTags(*excludeTags.toTypedArray())
        }
    }

    // Every `-Pjqwik.*` Gradle property reaches the test JVM and wins over src/test/resources/junit-platform.properties:
    // `gradlew check -Pjqwik.tries.default=20000` for a deeper run, `-Pjqwik.reporting.onlyfailures=false` to report every
    // property (tries, seed). See docs/TESTS.md.
    project.properties.filterKeys { it.startsWith("jqwik.") }.forEach { (name, value) -> systemProperty(name, value.toString()) }

    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.STANDARD_OUT)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.jar {
    archiveFileName = "calc.jar"
    manifest {
        attributes("Main-Class" to application.mainClass)
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Prints the home of the JDK used by the toolchain; package.ps1 needs the matching `jpackage`.
tasks.register("printToolchainHome") {
    val launcher = javaToolchains.launcherFor(java.toolchain)
    doLast {
        println(launcher.get().metadata.installationPath.asFile.absolutePath)
    }
}
