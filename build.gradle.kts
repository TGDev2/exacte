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

    // Forward the switch that turns "no display" into a failure for the `ui` tests (used by CI),
    // e.g. `gradlew check -Dcalc.ui.required=true`. Off by default: without a display they are skipped.
    systemProperty("calc.ui.required", System.getProperty("calc.ui.required", "false"))

    // `-Djava.awt.headless=true` given to Gradle only reaches the Gradle JVM, never the forked test JVM:
    // forward it explicitly so `gradlew check -Djava.awt.headless=true` really runs the tests without a display.
    System.getProperty("java.awt.headless")?.let { systemProperty("java.awt.headless", it) }

    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
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
