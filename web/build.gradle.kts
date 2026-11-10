import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.teavm.gradle.api.JSModuleType

// The core compiled to JavaScript by TeaVM. Decisions: the "Calc dans le navigateur" plan, D1 to D3.
// Trial and measures: docs/essais/teavm-essai.md.
//
//   gradlew :web:generateJavaScript   -> web/build/generated/teavm/js/calc.js
//   gradlew :web:site                 -> web/build/site, the static page with calc.js
//   gradlew :web:webTest              -> the tests that run Node or a browser, kept out of the lifecycle of the build (D3)

plugins {
    java
    id("org.teavm") version "0.16.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -proc:none: the plugin adds an annotation processor this module does not use, and -Xlint:processing would refuse it.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-proc:none"))
}

val webTestSources = sourceSets.create("webTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    implementation(project(":"))
    // The extension that completes the class library of TeaVM runs inside the compiler, never in the browser.
    compileOnly("org.teavm:teavm-core:0.16.0")

    "webTestImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "webTestImplementation"("org.junit.jupiter:junit-jupiter")
    "webTestImplementation"("net.jqwik:jqwik:1.10.2")
    "webTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

teavm {
    js {
        mainClass = "calc.web.WebExports"
        targetFileName = "calc.js"
        moduleType = JSModuleType.UMD
        obfuscated = true
    }
}

tasks.register<JavaExec>("generateCharacterCompat") {
    description = "Writes CharacterCompat from the classification of the JDK of the toolchain."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "calc.web.tools.CharacterCompatGenerator"
    args(file("src/main/java/calc/web/compat/CharacterCompat.java").path)
}

tasks.register<Sync>("site") {
    description = "Assembles the static page and the compiled core under build/site."
    from("site")
    from(tasks.named("generateJavaScript"))
    into(layout.buildDirectory.dir("site"))
    // The report files are added after this copy: a run with another value of the property, or none, must copy again so
    // that Sync removes the files of the earlier run.
    inputs.property("conformityReport", providers.gradleProperty("conformityReport").getOrElse(""))
}

// With -PconformityReport=<file>, the conformity report joins the site: rapport-conformite.html and rapport-conformite.md.
// The path is relative to the root of the repository (rootProject.file: a plain file() would look under web/). Without the
// property, nothing changes: a trial on this machine has no report, and the site holds none.
if (providers.gradleProperty("conformityReport").isPresent) {
    val writeReportPage = tasks.register<JavaExec>("writeReportPage") {
        description = "Adds the conformity report (-PconformityReport=<file>) to build/site."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "calc.web.tools.ReportPageMain"
        args(
            rootProject.file(providers.gradleProperty("conformityReport").get()).path,
            layout.buildDirectory.dir("site").get().asFile.path,
        )
    }
    tasks.named("site") { finalizedBy(writeReportPage) }
}

tasks.register<Test>("webTest") {
    group = "verification"
    description = "Runs the tests that need Node or a browser against the code compiled by TeaVM."
    testClassesDirs = webTestSources.output.classesDirs
    classpath = webTestSources.runtimeClasspath
    useJUnitPlatform()
    // Every path of these tests is relative to the root of the repository.
    workingDir = rootDir
    // Node, the scripts and the documents they read are not inputs of this task.
    outputs.upToDateWhen { false }
    // The test counter of the release gate adds up what is under web/build/test-results: that folder stays empty.
    reports.junitXml.outputLocation = layout.buildDirectory.dir("webtest-results")
    binaryResultsDirectory = layout.buildDirectory.dir("webtest-results/binary")
    systemProperty("calc.web.browser.required", providers.gradleProperty("requireBrowser").orElse("false").get())
    systemProperty("calc.web.parity.seed", rootProject.extra["paritySeedValue"]!!)
    systemProperty("calc.web.parity.corpus", rootProject.extra["parityCorpusPath"]!!)
    dependsOn("generateJavaScript", "site", ":cyclonedxBom", ":writeParityCorpus")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
