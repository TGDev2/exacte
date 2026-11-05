import java.io.File
import java.time.Instant
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// ---------------------------------------------------------------------------------------------------------------------
// Conformity report: one Markdown file built from the REAL results of the build (JUnit XML, JaCoCo XML, PIT XML).
//
//   gradlew conformity            -> check + pitest + conformityReport
//   gradlew check pitest conformityReport
//
// The task reads what is on disk and does not run the tests itself: it fails when a source file is missing or empty,
// or when a threshold of config/quality-thresholds.properties is not met, instead of writing a report that lies.
// Thresholds, measures and strategy: docs/TESTS.md ("Couverture, mutation et rapport de conformité").
// ---------------------------------------------------------------------------------------------------------------------

/** Parsing and arithmetic shared by the report; plain functions so that they stay readable. */
object Conformity {
    fun parse(file: File): Element {
        require(file.isFile && file.length() > 0) { "Source data missing or empty: $file" }
        val factory = DocumentBuilderFactory.newInstance()
        // JaCoCo's XML declares an external DTD that is neither needed nor available offline.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    fun children(element: Element, name: String): List<Element> {
        val nodes = element.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }
    }

    fun percent(covered: Long, total: Long): Double = if (total == 0L) 100.0 else 100.0 * covered / total

    fun format(value: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", value) + " %"

    fun cell(text: String): String = text.replace("|", "\\|").replace("\n", " ")

    fun table(header: List<String>, rows: List<List<String>>): String = buildString {
        appendLine("| " + header.joinToString(" | ") + " |")
        appendLine("|" + header.joinToString("|") { "---" } + "|")
        rows.forEach { appendLine("| " + it.joinToString(" | ") { c -> cell(c) } + " |") }
    }
}

class ClassResult(val name: String, val tests: Int, val failed: Int, val skipped: Int) {
    val passed get() = tests - failed - skipped
}

class Coverage(val linesCovered: Long, val linesTotal: Long, val branchesCovered: Long, val branchesTotal: Long) {
    val line get() = Conformity.percent(linesCovered, linesTotal)
    val branch get() = Conformity.percent(branchesCovered, branchesTotal)
}

class Mutation(val total: Long, val detected: Long, val survived: Long, val noCoverage: Long) {
    val score get() = Conformity.percent(detected, total)
}

abstract class ConformityReportTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val junitResults: ConfigurableFileCollection

    @get:InputFile
    abstract val jacocoXml: RegularFileProperty

    @get:InputFile
    abstract val pitestXml: RegularFileProperty

    @get:InputFile
    abstract val thresholds: RegularFileProperty

    @get:InputFile
    abstract val testsDoc: RegularFileProperty

    @get:InputDirectory
    abstract val testSources: DirectoryProperty

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val jdkDescription: Property<String>

    @get:Input
    abstract val commit: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        // The report states the date and the machine: it is rebuilt every time it is asked for.
        outputs.upToDateWhen { false }
    }

    /** Class name -> JUnit tags written on the class (`@Tag("ui")`, also `@net.jqwik.api.Tag`). */
    private fun tagsOf(className: String): List<String> {
        val file = testSources.get().asFile.resolve(className.replace('.', '/') + ".java")
        if (!file.isFile) return emptyList()
        val tags = mutableListOf<String>()
        for (line in file.readLines()) {
            val trimmed = line.trim()
            Regex("""@(?:net\.jqwik\.api\.)?Tag\("([a-z-]+)"\)""").matchEntire(trimmed)?.let { tags += it.groupValues[1] }
            if (Regex("""(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s""").containsMatchIn(trimmed) && !trimmed.startsWith("@")) break
        }
        return tags
    }

    private fun readJunit(): List<ClassResult> {
        val files = junitResults.files.filter { it.name.startsWith("TEST-") && it.extension == "xml" }.sortedBy { it.name }
        require(files.isNotEmpty()) { "No JUnit XML result found: run the tests first (gradlew check)." }
        return files.map {
            val suite = Conformity.parse(it)
            ClassResult(
                suite.getAttribute("name"),
                suite.getAttribute("tests").toInt(),
                suite.getAttribute("failures").toInt() + suite.getAttribute("errors").toInt(),
                suite.getAttribute("skipped").toInt(),
            )
        }
    }

    private fun readJacoco(): Map<String, Coverage> {
        val report = Conformity.parse(jacocoXml.get().asFile)
        val packages = Conformity.children(report, "package")
        require(packages.isNotEmpty()) { "The JaCoCo report lists no package: ${jacocoXml.get().asFile}" }
        return packages.associate { pkg ->
            fun counter(type: String): Pair<Long, Long> {
                val c = Conformity.children(pkg, "counter").firstOrNull { it.getAttribute("type") == type }
                    ?: return 0L to 0L
                val covered = c.getAttribute("covered").toLong()
                return covered to covered + c.getAttribute("missed").toLong()
            }
            val (lc, lt) = counter("LINE")
            val (bc, bt) = counter("BRANCH")
            pkg.getAttribute("name").replace('/', '.') to Coverage(lc, lt, bc, bt)
        }
    }

    /** PIT: a mutant is detected when it is killed, times out or exhausts memory; non-viable mutants do not count. */
    private fun readPit(): Map<String, Mutation> {
        val root = Conformity.parse(pitestXml.get().asFile)
        val mutations = Conformity.children(root, "mutation")
        require(mutations.isNotEmpty()) { "The PIT report lists no mutation: ${pitestXml.get().asFile}" }
        return mutations.filter { it.getAttribute("status") != "NON_VIABLE" }
            .groupBy { m -> Conformity.children(m, "mutatedClass").single().textContent.substringBeforeLast('.') }
            .mapValues { (_, list) ->
                Mutation(
                    list.size.toLong(),
                    list.count { it.getAttribute("detected") == "true" }.toLong(),
                    list.count { it.getAttribute("status") == "SURVIVED" }.toLong(),
                    list.count { it.getAttribute("status") == "NO_COVERAGE" }.toLong(),
                )
            }
    }

    private class Guarantee(val id: String, val text: String, val classes: List<String>)

    private fun readGuarantees(): List<Guarantee> {
        val rows = testsDoc.get().asFile.readLines().filter { Regex("""^\|\s*G\d+\s*\|""").containsMatchIn(it) }
        require(rows.isNotEmpty()) { "docs/TESTS.md has no guarantee table (rows starting with | G01 |)." }
        return rows.map { row ->
            val cells = row.trim().trim('|').split(Regex("""(?<!\\)\|""")).map { it.trim() }
            require(cells.size == 3) { "Malformed guarantee row: $row" }
            Guarantee(cells[0], cells[1], Regex("`([A-Za-z0-9_]+)`").findAll(cells[2]).map { it.groupValues[1] }.toList())
        }
    }

    private fun readLimits(): List<String> {
        val lines = testsDoc.get().asFile.readLines()
        val start = lines.indexOfFirst { it.trim() == "## Limites connues et points non vérifiés" }
        require(start >= 0) { "docs/TESTS.md has no section '## Limites connues et points non vérifiés'." }
        val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.startsWith("- ") }
        require(section.isNotEmpty()) { "The limits section of docs/TESTS.md is empty." }
        return section
    }

    @TaskAction
    fun generate() {
        val classes = readJunit()
        val byName = classes.associateBy { it.name }
        val coverage = readJacoco()
        val mutation = readPit()
        val props = Properties().apply { thresholds.get().asFile.reader(Charsets.UTF_8).use { load(it) } }
        val guarantees = readGuarantees()
        val limits = readLimits()
        val problems = mutableListOf<String>()

        // --- Tests per tag -------------------------------------------------------------------------------------
        val tags = linkedSetOf("(sans étiquette)", "property", "robustness", "ui")
        val perTag = classes.flatMap { c -> tagsOf(c.name).ifEmpty { listOf("(sans étiquette)") }.map { it to c } }
            .groupBy({ it.first }, { it.second })
        perTag.keys.forEach { tags += it }
        val tagRows = tags.filter { perTag.containsKey(it) }.map { tag ->
            val list = perTag.getValue(tag)
            val skipped = list.sumOf { it.skipped }
            listOf(
                tag, list.sumOf { it.tests }.toString(), list.size.toString(), list.sumOf { it.passed }.toString(),
                list.sumOf { it.failed }.toString(),
                if (skipped > 0) "$skipped (IGNORÉS : non exécutés sur cette machine)" else "0",
            )
        }
        val totalTests = classes.sumOf { it.tests }
        val totalFailed = classes.sumOf { it.failed }
        val totalSkipped = classes.sumOf { it.skipped }
        if (totalFailed > 0) problems += "$totalFailed test(s) en échec."
        val uiClasses = perTag["ui"].orEmpty()
        val uiSkipped = uiClasses.sumOf { it.skipped }
        if (uiClasses.isEmpty()) problems += "Aucune classe de test d'interface (étiquette ui) n'a produit de résultat."

        // --- Property tries ------------------------------------------------------------------------------------
        val triesPattern = Regex("""tries\s*=\s*(\d+)\s*\|\s*# of calls to property""")
        val tries = junitResults.files.filter { it.name.startsWith("TEST-") }.flatMap { file ->
            triesPattern.findAll(file.readText()).map { it.groupValues[1].toLong() }.toList()
        }
        val triesText = if (tries.isEmpty()) {
            "non disponible : jqwik ne rapporte les essais que pour les propriétés en échec " +
                "(relancer avec `-Pjqwik.reporting.onlyfailures=false` pour les rapporter)"
        } else {
            "${tries.size} propriétés rapportées, ${tries.sum()} essais"
        }

        // --- Coverage -------------------------------------------------------------------------------------------
        val gatedCoverage = props.stringPropertyNames().filter { it.startsWith("jacoco.line.") }
            .map { it.removePrefix("jacoco.line.") }.sorted()
        val coverageRows = coverage.keys.sorted().map { pkg ->
            val c = coverage.getValue(pkg)
            val lineMin = props.getProperty("jacoco.line.$pkg")?.toDouble()
            val branchMin = props.getProperty("jacoco.branch.$pkg")?.toDouble()
            var status = "non contrôlé (dépend de l'affichage)".takeIf { lineMin == null } ?: "OK"
            if (lineMin != null && c.line < lineMin) { status = "ÉCHEC"; problems += "Couverture des lignes de $pkg : ${Conformity.format(c.line)} < $lineMin %." }
            if (branchMin != null && c.branch < branchMin) { status = "ÉCHEC"; problems += "Couverture des branches de $pkg : ${Conformity.format(c.branch)} < $branchMin %." }
            listOf(
                "`$pkg`", "${c.linesCovered}/${c.linesTotal} (${Conformity.format(c.line)})",
                lineMin?.let { "≥ ${it.toInt()} %" } ?: "-",
                "${c.branchesCovered}/${c.branchesTotal} (${Conformity.format(c.branch)})",
                branchMin?.let { "≥ ${it.toInt()} %" } ?: "-", status,
            )
        }
        gatedCoverage.filter { !coverage.containsKey(it) }.forEach { problems += "Package $it : absent du rapport JaCoCo." }

        // --- Mutation -------------------------------------------------------------------------------------------
        val gatedMutation = props.stringPropertyNames().filter { it.startsWith("mutation.") }.map { it.removePrefix("mutation.") }.sorted()
        gatedMutation.filter { !mutation.containsKey(it) }.forEach { problems += "Package $it : absent du rapport PIT." }
        val mutationRows = mutation.keys.sorted().map { pkg ->
            val m = mutation.getValue(pkg)
            val min = props.getProperty("mutation.$pkg")?.toDouble()
            var status = if (min == null) "non contrôlé" else "OK"
            if (min != null && m.score < min) { status = "ÉCHEC"; problems += "Score de mutation de $pkg : ${Conformity.format(m.score)} < $min %." }
            listOf(
                "`$pkg`", m.total.toString(), m.detected.toString(), m.survived.toString(), m.noCoverage.toString(),
                Conformity.format(m.score), min?.let { "≥ ${it.toInt()} %" } ?: "-", status,
            )
        }
        val allMutants = mutation.values
        val mutationTotal = Mutation(allMutants.sumOf { it.total }, allMutants.sumOf { it.detected },
            allMutants.sumOf { it.survived }, allMutants.sumOf { it.noCoverage })

        // --- Guarantees -----------------------------------------------------------------------------------------
        val guaranteeRows = guarantees.map { g ->
            if (g.classes.isEmpty()) {
                problems += "Garantie ${g.id} sans classe de test."
                return@map listOf(g.id, g.text, "-", "ORPHELINE")
            }
            val missing = g.classes.filter { name -> byName.keys.none { it.substringAfterLast('.') == name } }
            if (missing.isNotEmpty()) problems += "Garantie ${g.id} : aucun résultat pour ${missing.joinToString()}."
            val found = g.classes.flatMap { name -> byName.values.filter { it.name.substringAfterLast('.') == name } }
            val tests = found.sumOf { it.tests }
            val failed = found.sumOf { it.failed }
            val skipped = found.sumOf { it.skipped }
            val status = when {
                missing.isNotEmpty() -> "SANS RÉSULTAT"
                failed > 0 -> "ÉCHEC ($failed)"
                skipped > 0 -> "PARTIELLE ($skipped ignorés sur $tests)"
                else -> "prouvée ($tests tests)"
            }
            listOf(g.id, g.text, g.classes.joinToString(", ") { "`$it`" }, status)
        }

        // --- Document ---------------------------------------------------------------------------------------------
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        val conforming = problems.isEmpty()
        val text = buildString {
            appendLine("# Rapport de conformité de Calc")
            appendLine()
            appendLine("**Verdict : ${if (conforming) "CONFORME" else "NON CONFORME"}** — ce rapport est généré par `gradlew conformityReport` " +
                "à partir des résultats réels des tests, de JaCoCo et de PIT ; il échoue si une donnée manque.")
            if (!conforming) {
                appendLine()
                problems.forEach { appendLine("- ${Conformity.cell(it)}") }
            }
            appendLine()
            appendLine("## Environnement")
            appendLine()
            appendLine("- Version : `${appVersion.get()}`")
            appendLine("- Commit : `${commit.get()}`")
            appendLine("- Date (UTC) : ${Instant.now()}")
            appendLine("- Système : $os")
            appendLine("- JDK des tests : ${jdkDescription.get()}")
            appendLine()
            appendLine("## Tests")
            appendLine()
            appendLine("Total : **$totalTests** tests dans ${classes.size} classes — ${totalTests - totalFailed - totalSkipped} réussis, " +
                "$totalFailed échoués, $totalSkipped ignorés.")
            appendLine()
            append(Conformity.table(listOf("Étiquette", "Tests", "Classes", "Réussis", "Échoués", "Ignorés"), tagRows))
            appendLine()
            if (uiSkipped > 0) {
                appendLine("**Attention : $uiSkipped test(s) d'interface ont été IGNORÉS (pas d'affichage sur cette machine).** " +
                    "L'interface n'a pas été exécutée pour ce rapport ; la CI les rend obligatoires (`-PrequireUiTests=true`).")
            } else {
                appendLine("Les ${uiClasses.sumOf { it.tests }} tests d'interface (étiquette `ui`) ont tous été exécutés (aucun ignoré).")
            }
            appendLine()
            appendLine("Essais de propriétés (jqwik) : $triesText.")
            appendLine()
            appendLine("## Couverture de code (JaCoCo)")
            appendLine()
            append(Conformity.table(listOf("Package", "Lignes", "Seuil", "Branches", "Seuil", "Statut"), coverageRows))
            appendLine()
            appendLine("## Tests de mutation (PIT)")
            appendLine()
            appendLine("Mutants viables : ${mutationTotal.total}, détectés : ${mutationTotal.detected}, survivants : ${mutationTotal.survived}, " +
                "sans couverture : ${mutationTotal.noCoverage}, score global : ${Conformity.format(mutationTotal.score)}. " +
                "Un mutant est détecté s'il est tué ou s'il dépasse le délai ; les mutants non viables sont exclus.")
            appendLine()
            append(Conformity.table(listOf("Package", "Mutants", "Détectés", "Survivants", "Sans couverture", "Score", "Seuil", "Statut"), mutationRows))
            appendLine()
            appendLine("## Garanties")
            appendLine()
            appendLine("Reprises de `docs/TESTS.md` : ${guarantees.size} garanties, ${guaranteeRows.count { it[3].startsWith("prouvée") }} prouvées par des tests exécutés, " +
                "${guarantees.count { it.classes.isEmpty() }} sans test.")
            appendLine()
            append(Conformity.table(listOf("Id", "Garantie", "Classes de test", "Statut"), guaranteeRows))
            appendLine()
            appendLine("## Limites connues et points non vérifiés")
            appendLine()
            limits.forEach { appendLine(it) }
        }
        val dir = outputDirectory.get().asFile
        dir.mkdirs()
        dir.resolve("RAPPORT-CONFORMITE.md").writeText(text, Charsets.UTF_8)
        logger.lifecycle("Conformity report: ${dir.resolve("RAPPORT-CONFORMITE.md")}")
        if (!conforming) {
            throw GradleException("Non-conformity (report written anyway):\n" + problems.joinToString("\n") { " - $it" })
        }
    }
}

fun gitOutput(vararg args: String): Provider<String> =
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }

val conformityReport = tasks.register<ConformityReportTask>("conformityReport") {
    group = "verification"
    description = "Writes build/reports/conformite/RAPPORT-CONFORMITE.md from the real test, coverage and mutation results."
    val build = layout.buildDirectory
    junitResults.from(build.dir("test-results/test").map { it.asFileTree.matching { include("TEST-*.xml") } })
    jacocoXml = build.file("reports/jacoco/test/jacocoTestReport.xml")
    pitestXml = build.file("reports/pitest/mutations.xml")
    thresholds = layout.projectDirectory.file("config/quality-thresholds.properties")
    testsDoc = layout.projectDirectory.file("docs/TESTS.md")
    testSources = layout.projectDirectory.dir("src/test/java")
    appVersion = project.version.toString()
    val toolchains = extensions.getByType<JavaToolchainService>()
    val launcher = toolchains.launcherFor(extensions.getByType<JavaPluginExtension>().toolchain).map { it.metadata }
    jdkDescription = launcher.map { "${it.vendor} ${it.jvmVersion}" }
    commit = gitOutput("rev-parse", "--short=12", "HEAD").zip(gitOutput("status", "--porcelain")) { head, status ->
        val id = head.ifEmpty { "inconnu (hors dépôt git)" }
        if (status.isNotEmpty()) "$id (+ modifications non commitées)" else id
    }
    outputDirectory = build.dir("reports/conformite")
    mustRunAfter("test", "jacocoTestReport", "pitest")
}

tasks.register("conformity") {
    group = "verification"
    description = "Runs check and the mutation tests, then writes the conformity report."
    dependsOn("check", "pitest", conformityReport)
}
