# 0012 - Packaging and releases: one Gradle task per system, archived application images, automatic GitHub Releases

## Status (Statut)

Accepted. Date: 2026-10-27. Epic E14.

## Context (Contexte)

Until E13 the only packaging was `package.ps1`: it called the `jpackage` of the toolchain on Windows and produced
`dist\Calc\Calc.exe` and `dist\Calc-portable.zip`, for version `1.0.0` written in the script, with the runtime image jpackage builds
by default (it embeds the modules jlink picks for the jar, with no check by the project). Nothing existed for macOS and Linux, nothing
published a file, and nothing proved that the jar is the same from one build to the next.

What was checked before deciding (July-October 2026 documentation and tools, run on 2026-10-27):

- The application has **no third-party runtime dependency** (`runtimeClasspath` is empty; the JSON reader is part of the code). The
  jar is the only file to ship.
- The toolchain JDK (Temurin 21.0.7) contains `jpackage` and `jdeps` in `bin/`.
- `jpackage` (Java 21 manual): `--type app-image` builds the image without any installer tool; the other types need tools on the
  machine: WiX 3 or later for `exe` / `msi`, Xcode command line tools for some `dmg` / `pkg` cases, `fakeroot` (Ubuntu) or `rpm-build`
  (Red Hat) for `deb` / `rpm`. Each format must be built on the system it targets. The manual does **not** state the macOS version rule.
  The rule comes from the JDK source (`CFBundleVersion`): the first component of `--app-version` must not be 0.
- The GitHub Windows Server 2025 runner image (the image README, read on 2026-10-27; that `windows-latest` points to it was not checked) lists WiX Toolset 3.14.1.8722, Java 21.0.12 and Gradle 9.8
  (runner-images README). The same check was not made for the macOS and Linux installer tools. The local machine has no WiX.
- CycloneDX Gradle plugin: version **3.5.1** on the Gradle Plugin Portal (created 2026-10-09), Gradle 8.4 or newer, supports the
  configuration cache; the project uses Gradle 9.8.2.

## Decision (Décision)

### 1. `jpackage` of the toolchain, called by Gradle tasks of the project

We will not add a packaging plugin. `build.gradle.kts` defines three small task types that run the tools **of the toolchain JDK**
(the JDK that compiled the classes, so the embedded runtime can run them): `ComputeModules` (`jdeps`), `PackageAppImage` (`jpackage`)
and `ArchiveImageAsTarGz` (`tar`). A packaging plugin would add a dependency to maintain for about a hundred lines that are easy to read
and that the project controls.

The chain is `jar` -> `stageAppInput` (jar and runtime dependencies in `build/packaging/input`) -> `appModules` -> `packageAppImage`
(`build/packaging/image`) -> `archiveAppImage` -> **`packageApp`**, the only task a person runs. `package.ps1` now only calls
`gradlew.bat check packageApp -PappVersion=<version>` and returns its exit code.

### 2. Modules computed, not listed

`appModules` runs `jdeps --print-module-deps --ignore-missing-deps --multi-release 21` on the jar and its runtime dependencies.
Result for this code: `java.base,java.desktop,java.logging,java.prefs`. `jpackage` adds the modules those need (the image lists
`java.base java.datatransfer java.xml java.prefs java.desktop java.logging jdk.localedata`).

`jdk.localedata` is the one module added by hand, with a proof. jdeps cannot see it: the locale data is reached through a service, not
through a reference in the bytecode. The code formats a number with `NumberFormat.getIntegerInstance(Locale.FRANCE)`
(`NotebookPresenter`). Measured with `jlink` on the toolchain JDK: without the module the result is `16,384` (U+002C); with it
`16 384` (U+202F, the French grouping separator). The list `extraModules` in `build.gradle.kts` carries this reason.

### 3. Application images are mandatory, native installers are not built

Every release carries one archived **application image** per system. Native installers (`msi` / `exe` with WiX, `dmg` / `pkg`,
`deb` / `rpm`) are **not** part of E14: the brief allows them only when the tooling is verified on the runners, and that cannot be
verified from here (WiX is absent locally, GitHub Actions cannot be run). They are a follow-up (see `docs/RELEASE.md`, "Limites"):
the same `PackageAppImage` task would get a sibling task per format, enabled only when its tool is found.

### 4. Version scheme

- A release is a tag **`vX.Y.Z`** with X >= 1 (no pre-release suffix). The build version is `X.Y.Z`.
- Gradle reads it from `-PappVersion=X.Y.Z`. The default `0.0.0-local` is not publishable: `packageApp` refuses it, and refuses any
  version that is not `X.Y.Z` with X >= 1 (`checkAppVersion`). The same rule applies on all systems, so a tag that builds on Linux also
  builds on macOS (where `jpackage` rejects a first component of 0) instead of failing on one runner only.
- In CI the version is derived from the tag (`v1.2.3` -> `1.2.3`) and validated by the same pattern before any build starts.
- `workflow_dispatch` takes a version `X.Y.Z` and publishes a **pre-release** whose tag is `test-vX.Y.Z-<run number>`. That name does not
  match `v*.*.*`, so a trial never triggers the release workflow again and never creates a version tag.
- `package.ps1` uses `1.0.0` by default (the value the script always had); official files come only from the workflow.

### 5. File names

| File | Name |
|---|---|
| Windows image | `Calc-<version>-windows-x64.zip` (contains `Calc\Calc.exe`) |
| macOS image | `Calc-<version>-macos-arm64.tar.gz` (contains `Calc.app`) |
| Linux image | `Calc-<version>-linux-x64.tar.gz` (contains `Calc/bin/Calc`) |
| SBOM | `Calc-<version>-sbom.cdx.json` |
| Sums | `SHA256SUMS.txt`, one line per file above, bare file names (`sha256sum -c` works in the download folder) |

The architecture is that of the machine that builds: `windows-latest` and `ubuntu-latest` are x64, `macos-latest` is arm64. The workflow
checks that exactly the three archives above exist before publishing. An Intel macOS build and a Linux arm64 build are not produced.
Windows uses a Gradle `Zip`; macOS and Linux use the system `tar`, which keeps permissions and the symbolic links of a `.app` bundle.

### 6. Reproducibility: the jar only

`tasks.jar` already fixed the file order and the time stamps (`isReproducibleFileOrder`, `isPreserveFileTimestamps = false`), and
the file name does not carry the version, so the version does not change the jar. Checked (E14): two `gradlew clean jar --rerun-tasks
--no-build-cache` give the same SHA-256 (`docs/RELEASE.md`). CI repeats the check on demand (the workflow is started by hand), on Linux only (job
`reproducible-jar` of `ci.yml`).

The application **image** is not promised to be reproducible, and the CI does not check it. Two local Windows builds of the image and of
the zip happened to be identical, which proves nothing about other conditions: the runtime is a copy of the JDK installed on the runner
(`setup-java` takes the latest Temurin 21 patch, so two releases built weeks apart embed different runtimes); the `tar.gz` of macOS and
Linux stores the modification times of the files and the time of the gzip header; the macOS image was never built here. Reproducing the image would need a pinned JDK build and a normalised archive: out of scope.

### 7. SBOM

The `org.cyclonedx.bom` plugin (3.5.1) is applied; `cyclonedxDirectBom` is limited to `runtimeClasspath`, the main component is typed
`application`, and only the JSON file is kept (CycloneDX 1.6). `gradlew cyclonedxBom` writes `build/reports/cyclonedx/bom.json`; the
workflow copies it to `Calc-<version>-sbom.cdx.json`.

What it contains today: the component `calc` and **no other component**, because the application has no third-party runtime
library. This is the true answer, not a gap in the plugin. What it does not describe: the JDK runtime embedded in each image (the plugin
only reads Gradle configurations). A later Epic can add it as a component by hand.

### 8. Release workflow (`.github/workflows/release.yml`)

Jobs: `prepare` (validates the version once), `build` (matrix `ubuntu-latest`, `windows-latest`, `macos-latest`: `check` with
`-PrequireUiTests=true` and xvfb on Linux as in `ci.yml`, `packageApp`, a smoke test that unpacks the archive, checks the embedded modules, starts the application and checks it is alive after 8 s with no Java error in its output (no window check),
upload), `publish` (Linux: downloads the three archives, generates the SBOM, writes `SHA256SUMS.txt`, writes the notes from the commit
subjects since the previous `vX.Y.Z` tag, creates the Release with `gh release create`).

Permissions: `contents: read` for the workflow, `contents: write` **only** on `publish`. In that job the checkout does not keep the
token (`persist-credentials: false`) and `GH_TOKEN` is given to the `gh release create` step alone, so Gradle (the build script and the
CycloneDX plugin run in `publish` to produce the SBOM) never sees a token that can write. Values coming from the event (tag name, input)
reach the scripts through `env`, never inside the script text. Every action is pinned by commit SHA like in `ci.yml`
(`download-artifact` v8.0.2 added: `9000827...`). The release is created by `gh`, which is preinstalled on the runner, so no further third-party action is needed.

### 9. Signing and notarisation: out of scope

Nothing is signed. For the user this means: Windows SmartScreen shows « Windows a protégé votre ordinateur » for a file downloaded
from the internet; macOS Gatekeeper refuses to open `Calc.app` from a browser download until the user allows it; on Linux there is no
such gate. The SHA-256 sums let a user check that the file is the published one, **not** who published it. `docs/RELEASE.md` explains how
to open the application anyway.

## Alternatives considered (Alternatives considérées)

- **A jpackage Gradle plugin** (for example the Badass family): rejected, one more dependency to keep up to date for what three short
  task types do; the project would also depend on how the plugin picks the JDK.
- **`Exec` tasks written inline** instead of task types: rejected, `Exec` with an output to capture and a directory to clean in `doFirst`
  needs the project at execution time; the task types use injected services and declare their inputs and outputs, so the tasks are
  up to date correctly.
- **A fixed module list**: rejected by the brief; the list would silently go stale when the code starts using a new module. `jdeps`
  runs at each packaging.
- **`jlink` with a custom runtime image** passed to `--runtime-image`: more control, but more code and a second tool to keep in step;
  `jpackage` already calls `jlink` with `--add-modules`.
- **Native installers now**: see decision 3.
- **A different version rule per system** (accept `0.x` on Windows and Linux, refuse on macOS): rejected, a release would then fail on one
  runner after the other two succeeded.
- **Release notes by `gh release create --generate-notes`**: rejected, the brief asks for the commit messages since the previous tag;
  GitHub's notes list pull requests.

## Consequences (Conséquences)

- `gradlew packageApp -PappVersion=X.Y.Z` is the same command on the three systems; `package.ps1` is a wrapper.
- The first real run of `release.yml` happens after the merge. Until then the workflow is only parsed (YAML) and its shell steps were
  checked piece by piece on Windows; the macOS and Linux packaging has never been executed (see the report of E14 and `docs/RELEASE.md`).
  Procedure: run the workflow by hand with a test version, check the pre-release, then delete it.
- A tag pushed on a commit that is not on `main` is built and published as well; the workflow does not check where the tag points.
- `package.ps1` no longer writes `dist\Calc-portable.zip` and `dist\Calc\`; the result is `build\release\Calc-<version>-windows-<arch>.zip`
  (`dist/` is still ignored by git).
- Each release depends on the Temurin 21 patch found on the runners at that time.
- Risks left: no signature (decision 9); the SBOM does not list the embedded runtime (decision 7); the Intel Mac and Linux arm64 are not served.
