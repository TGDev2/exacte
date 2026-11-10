import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    application
    jacoco
    id("org.cyclonedx.bom") version "3.5.1"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "calc"
// `-PappVersion=1.2.3` sets the version. The default is deliberately not publishable: `packageApp` refuses it.
version = providers.gradleProperty("appVersion").orElse("0.0.0-local").get()
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
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Property-based testing engine for the JUnit Platform. 1.10.2 is built against JUnit Platform 1.14.4,
    // the one managed by the junit-bom above.
    testImplementation("net.jqwik:jqwik:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The JVM tests of calc.web. Not transitive: calc.jar must not come twice on the test classpath.
    testImplementation(project(":web")) { isTransitive = false }
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
    providers.gradlePropertiesPrefixedBy("jqwik.").get().forEach { (name, value) -> systemProperty(name, value) }

    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.STANDARD_OUT)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

// Coverage thresholds per package, fixed from the measures (config/quality-thresholds.properties, docs/TESTS.md).
val qualityThresholds = Properties().apply {
    file("config/quality-thresholds.properties").reader(Charsets.UTF_8).use { load(it) }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        qualityThresholds.stringPropertyNames().filter { it.startsWith("jacoco.line.") }.sorted().forEach { key ->
            val pkg = key.removePrefix("jacoco.line.")
            rule {
                element = "PACKAGE"
                includes = listOf(pkg)
                limit {
                    counter = "LINE"
                    minimum = BigDecimal(qualityThresholds.getProperty(key)).movePointLeft(2)
                }
                limit {
                    counter = "BRANCH"
                    minimum = BigDecimal(qualityThresholds.getProperty("jacoco.branch.$pkg")).movePointLeft(2)
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

apply(from = "gradle/conformity.gradle.kts")

// Mutation testing of the critical packages (measures, thresholds and CI strategy: docs/TESTS.md).
pitest {
    pitestVersion = "1.30.1"
    junit5PluginVersion = "1.2.3"
    // `-PpitTargets=calc.number.*` narrows a run to one package (faster while working on surviving mutants).
    targetClasses = providers.gradleProperty("pitTargets").map { it.split(",") }
        .orElse(listOf("calc.number.*", "calc.format.*", "calc.expression.*", "calc.notebook.*", "calc.persistence.*"))
    targetTests = listOf("calc.*")
    // The window tests need a display and do not exercise the mutated packages.
    excludedGroups = listOf("ui", "property", "robustness")
    // Tests of speed and of huge files: slow, and their budgets are meaningless under mutation.
    excludedTestClasses = listOf("calc.persistence.HostileFilesTest", "calc.persistence.NotebookHostileFilesTest", "calc.persistence.StoreLockTest", "*PerformanceTest", "*ResponsivenessTest", "calc.number.NumericLimitsTest", "calc.expression.ExpressionRobustnessTest")
    timeoutConstInMillis = 8000
    threads = Runtime.getRuntime().availableProcessors()
    outputFormats = listOf("XML", "HTML")
    timestampedReports = false
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

// Prints the home of the JDK used by the toolchain (the standalone tools under tools/ need a matching JDK).
tasks.register("printToolchainHome") {
    val launcher = javaToolchains.launcherFor(java.toolchain)
    doLast {
        println(launcher.get().metadata.installationPath.asFile.absolutePath)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Packaging: a self-contained application image per operating system, archived, plus a CycloneDX SBOM.
// Decisions and limits: docs/adr/0012-packaging-et-releases.md. Procedure: docs/RELEASE.md.
//
//   gradlew packageApp -PappVersion=1.2.3     -> build/release/Calc-1.2.3-<windows|macos|linux>-<x64|arm64>.(zip|tar.gz)
//   gradlew cyclonedxBom                      -> build/reports/cyclonedx/bom.json (runtime classpath only)
// ---------------------------------------------------------------------------------------------------------------------

val packagingOs = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "windows"
        it.contains("mac") -> "macos"
        else -> "linux"
    }
}
val packagingArch = System.getProperty("os.arch").lowercase().let {
    when (it) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> it
    }
}
val appName = "Calc"
val releaseVersionPattern = Regex("""[1-9][0-9]*\.[0-9]+\.[0-9]+""")

// The JDK of the toolchain compiled the classes: its jdeps and jpackage must be the ones that analyse and embed them.
val toolchainHome = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath }

fun toolchainTool(name: String): Provider<String> = toolchainHome.map {
    it.file("bin/" + name + if (packagingOs == "windows") ".exe" else "").asFile.absolutePath
}

val toolchainJdkVersion = javaToolchains.launcherFor(java.toolchain).map { it.metadata.jvmVersion }

val packagingDir = layout.buildDirectory.dir("packaging")

abstract class ComputeModules @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input
    abstract val jdeps: Property<String>

    // The version of the JDK is an input: a JDK updated in place under the same path must run the tool again.
    @get:Input
    abstract val jdkVersion: Property<String>

    @get:Input
    abstract val release: Property<Int>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ConfigurableFileCollection

    @get:OutputFile
    abstract val modulesFile: RegularFileProperty

    @TaskAction
    fun compute() {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            executable = jdeps.get()
            args("--print-module-deps", "--ignore-missing-deps", "--multi-release", release.get().toString())
            args(jars.files.sortedBy { it.name }.map { it.absolutePath })
            standardOutput = output
        }
        val modules = output.toString(Charsets.UTF_8).trim()
        check(modules.isNotEmpty() && modules.split(',').contains("java.base")) { "jdeps returned no module list: '$modules'" }
        modulesFile.get().asFile.writeText(modules + "\n")
    }
}

abstract class PackageAppImage @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:Input
    abstract val jpackage: Property<String>

    @get:Input
    abstract val jdkVersion: Property<String>

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val vendor: Property<String>

    @get:Input
    abstract val appDescription: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    /** Modules jdeps cannot see (loaded through services, not through a reference in the bytecode). */
    @get:Input
    abstract val extraModules: ListProperty<String>

    @get:InputFile
    abstract val modulesFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val imageDirectory: DirectoryProperty

    @TaskAction
    fun packageImage() {
        val modules = (modulesFile.get().asFile.readText().trim().split(',') + extraModules.get()).distinct().sorted()
        fileSystemOperations.delete { delete(imageDirectory) }
        imageDirectory.get().asFile.mkdirs()
        execOperations.exec {
            executable = jpackage.get()
            args(
                "--type", "app-image",
                "--name", appName.get(),
                "--dest", imageDirectory.get().asFile.absolutePath,
                "--input", inputDirectory.get().asFile.absolutePath,
                "--main-jar", "calc.jar",
                "--main-class", mainClass.get(),
                "--app-version", appVersion.get(),
                "--vendor", vendor.get(),
                "--description", appDescription.get(),
                "--add-modules", modules.joinToString(","),
            )
        }
    }
}

// macOS and Linux: the system `tar` keeps permissions and symbolic links, which a .app bundle needs.
abstract class ArchiveImageAsTarGz @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imageDirectory: DirectoryProperty

    @get:OutputFile
    abstract val archive: RegularFileProperty

    @TaskAction
    fun archiveImage() {
        val target = archive.get().asFile
        target.parentFile.mkdirs()
        target.delete()
        val entries = imageDirectory.get().asFile.list().orEmpty().sorted()
        execOperations.exec {
            executable = "tar"
            args("-czf", target.absolutePath, "-C", imageDirectory.get().asFile.absolutePath)
            args(entries)
            // bsdtar (macOS) would otherwise add AppleDouble "._" entries for extended attributes.
            environment("COPYFILE_DISABLE", "1")
        }
    }
}

val checkAppVersion = tasks.register("checkAppVersion") {
    description = "Refuses a version that jpackage or a release cannot use (only X.Y.Z with X >= 1)."
    val versionText = project.version.toString()
    doFirst {
        require(releaseVersionPattern.matches(versionText)) {
            "The version '$versionText' cannot be packaged: pass -PappVersion=X.Y.Z with X >= 1 " +
                "(jpackage refuses a first component of 0 on macOS, and a release tag is vX.Y.Z)."
        }
    }
}

val stageAppInput = tasks.register<Sync>("stageAppInput") {
    description = "Collects the application jar and its runtime dependencies in the jpackage input directory."
    dependsOn(checkAppVersion)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(packagingDir.map { it.dir("input") })
}

val appModules = tasks.register<ComputeModules>("appModules") {
    description = "Computes the JDK modules the application needs with jdeps (written to build/packaging/modules.txt)."
    jdeps = toolchainTool("jdeps")
    jdkVersion = toolchainJdkVersion
    release = java.toolchain.languageVersion.map { it.asInt() }
    jars.from(stageAppInput.map { it.outputs.files.asFileTree.matching { include("*.jar") } })
    modulesFile = packagingDir.map { it.file("modules.txt") }
}

val packageAppImage = tasks.register<PackageAppImage>("packageAppImage") {
    description = "Builds the self-contained application image with the jpackage of the toolchain."
    jpackage = toolchainTool("jpackage")
    jdkVersion = toolchainJdkVersion
    appName = "Calc"
    appVersion = project.version.toString()
    vendor = "Calc"
    appDescription = project.description.orEmpty()
    mainClass = application.mainClass
    // The code formats numbers with Locale.FRANCE: that locale data lives in jdk.localedata, which is reached through
    // the locale provider service, so jdeps never lists it. See ADR 0012.
    extraModules = listOf("jdk.localedata")
    modulesFile = appModules.flatMap { it.modulesFile }
    inputDirectory = stageAppInput.map { it.destinationDir }
    imageDirectory = packagingDir.map { it.dir("image") }
}

val releaseDir = layout.buildDirectory.dir("release")
val packageBaseName = "$appName-${project.version}-$packagingOs-$packagingArch"

val archiveAppImage = if (packagingOs == "windows") {
    tasks.register<Zip>("archiveAppImage") {
        from(packageAppImage.flatMap { it.imageDirectory })
        destinationDirectory = releaseDir
        archiveFileName = "$packageBaseName.zip"
    }
} else {
    tasks.register<ArchiveImageAsTarGz>("archiveAppImage") {
        imageDirectory = packageAppImage.flatMap { it.imageDirectory }
        archive = releaseDir.map { it.file("$packageBaseName.tar.gz") }
    }
}

tasks.register("packageApp") {
    group = "distribution"
    description = "Packages the application for this operating system: build/release/Calc-<version>-<os>-<arch>.(zip|tar.gz)."
    dependsOn(archiveAppImage)
    val archiveFile = releaseDir.map { it.file("$packageBaseName." + if (packagingOs == "windows") "zip" else "tar.gz") }
    doLast { println("Application package: ${archiveFile.get().asFile}") }
}

// The SBOM describes what the application ships: the runtime classpath only (no test library).
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
}

// :web is not part of what the application ships, and its classpath holds the TeaVM compiler: the aggregate leaves it out.
project(":web").tasks.matching { it.name == "cyclonedxDirectBom" }.configureEach { enabled = false }

// One JSON file is enough for the release (the XML twin is not published).
tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
    xmlOutput.unsetConvention()
}

tasks.cyclonedxBom {
    xmlOutput.unsetConvention()
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
}

// ---------------------------------------------------------------------------------------------------------------------
// Release notes: the [X.Y.Z] section of CHANGELOG.md with absolute links and a link to the installation guide.
//
//   gradlew releaseNotes -PappVersion=1.0.0   -> build/release-notes.md (fails if CHANGELOG.md has no such section)
//
// The tool lives in the test source set (src/test/java/calc/release), so it is tested and never shipped. ADR 0012.
// ---------------------------------------------------------------------------------------------------------------------

tasks.register<JavaExec>("releaseNotes") {
    group = "distribution"
    description = "Writes build/release-notes.md from the section of -PappVersion in CHANGELOG.md."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "calc.release.ReleaseNotes"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    val repository = providers.environmentVariable("GITHUB_REPOSITORY").orElse("TGDev2/Calculatrice")
    val ref = providers.environmentVariable("GITHUB_SHA").orElse("main")
    args(
        "CHANGELOG.md",
        project.version.toString(),
        repository.get(),
        ref.get(),
        layout.buildDirectory.file("release-notes.md").get().asFile.absolutePath,
    )
    // The changelog is read at the root of the repository, wherever Gradle was started.
    workingDir = projectDir
}

// ---------------------------------------------------------------------------------------------------------------------
// The files of a release: the six files in a folder, their checksums and contents, and the evidence of a trial.
// Normally run by tools/release/verify-release.sh, which downloads the files and fills in the properties below.
//
// The tool lives in the test source set (src/test/java/calc/release), so it is tested and never shipped. ADR 0012.
// ---------------------------------------------------------------------------------------------------------------------

tasks.register<JavaExec>("verifyReleaseFiles") {
    group = "verification"
    description = "Checks the release files of -PreleaseDir for -PappVersion and writes the evidence to -PevidenceFile."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "calc.release.ReleaseFilesCheck"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    val versionText = project.version.toString()
    // Read when the task runs: a build that does not run this task needs none of these properties.
    val values = listOf(
        "releaseDir",
        "releaseTag",
        "releaseRun",
        "releaseCommit",
        "releasePrerelease",
        "releaseNotesLink",
        "windowsStarted",
        "evidenceFile",
    ).associateWith { providers.gradleProperty(it) }
    doFirst {
        val missing = values.filterValues { !it.isPresent }.keys
        require(missing.isEmpty()) { "verifyReleaseFiles needs these properties: ${missing.joinToString { "-P$it" }}" }
        val given = values.values.map { it.get() }
        args(listOf(given[0], versionText) + given.drop(1))
    }
    workingDir = projectDir
}

// The corpus of the differential test of the web version. A CI run passes github.run_id as -PparitySeed.
val paritySeedValue = providers.gradleProperty("paritySeed").orElse("20261107").get()
val parityCorpusPath = layout.buildDirectory.file("web-parity/corpus.txt").get().asFile.path
extra["paritySeedValue"] = paritySeedValue
extra["parityCorpusPath"] = parityCorpusPath

tasks.register<JavaExec>("writeParityCorpus") {
    description = "Writes the notebooks that the JVM and Node replay: build/web-parity/corpus.txt, from -PparitySeed."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "calc.expression.ParityCorpusWriter"
    defaultCharacterEncoding = "UTF-8"
    args(parityCorpusPath, paritySeedValue)
}
