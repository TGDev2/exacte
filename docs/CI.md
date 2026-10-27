# Intégration continue

[![CI](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml/badge.svg)](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml)

## Ce que fait la CI

Le workflow [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) s'exécute :

- à chaque `push` sur `main` ;
- à chaque pull request ;
- à la demande (`workflow_dispatch`).

Une nouvelle exécution sur la même branche annule la précédente (`concurrency`). Le workflow n'a que le droit
`contents: read`.

Il tourne en parallèle sur `ubuntu-latest`, `windows-latest` et `macos-latest` (`fail-fast: false` : un échec sur un
système n'interrompt pas les autres). Chaque job :

1. récupère le dépôt ;
2. installe Temurin 21 (la toolchain Gradle du projet vise Java 21) ;
3. prépare Gradle avec `gradle/actions/setup-gradle` (cache, et validation de la somme de contrôle du wrapper : cette
   action la fait par défaut, l'action dédiée `wrapper-validation` n'est donc pas nécessaire) ;
4. lance `./gradlew check -PrequireUiTests=true` (sous Linux : `xvfb-run -a ./gradlew check -PrequireUiTests=true`, car
   les runners Linux n'ont pas d'écran ; `xvfb` est préinstallé sur `ubuntu-latest`) ;
5. publie les rapports de test comme artefacts, même en cas d'échec.

Un second job, `reproducible jar` (Linux seulement ; le JAR construit sous Windows et macOS n'est pas comparé), lance deux fois
`./gradlew clean jar --rerun-tasks --no-build-cache`, affiche les deux sommes SHA-256 de `build/libs/calc.jar` et échoue
si elles diffèrent. Il n'est pas dans la liste des vérifications exigées par la protection de `main` ci-dessous : l'ajouter
après sa première exécution si on veut en faire une condition de fusion.

Les publications ne passent pas par ce workflow mais par [`release.yml`](../.github/workflows/release.yml) (déclenché par un tag
`vX.Y.Z`, voir [RELEASE.md](RELEASE.md)). Il reprend les mêmes vérifications (`check` avec les tests d'interface obligatoires)
avant de construire les archives.

Les actions sont épinglées par SHA de commit, avec la version en commentaire. Dependabot
([`.github/dependabot.yml`](../.github/dependabot.yml)) propose chaque semaine la mise à jour des dépendances Gradle et
des actions GitHub (5 pull requests ouvertes au maximum par écosystème).

## Pourquoi les tests d'interface sont obligatoires en CI

Les tests portant le tag JUnit `ui` (`NotebookFrameTest`, `NotebookFrameFilesTest`, `NotebookFrameResponsivenessTest`, `MainStartupTest`) pilotent la vraie fenêtre Swing. Sans affichage ils sont
ignorés (`skipped`), et un build vert pourrait alors ne rien avoir vérifié de l'interface. Avec
`-PrequireUiTests=true`, l'absence d'affichage fait **échouer** ces tests avec le message
« UI tests require a graphical environment (-PrequireUiTests=true) ». Un job vert prouve donc que l'interface a été
exécutée sur ce système.

## Reproduire en local

```powershell
# Avec affichage (poste de développement) : les tests ui s'exécutent
.\gradlew.bat check -PrequireUiTests=true

# Comportement par défaut : sans affichage, les tests ui sont ignorés, le reste passe
.\gradlew.bat check "-Djava.awt.headless=true"

# Preuve de la garde : sans affichage et avec la propriété, le build échoue
.\gradlew.bat check -PrequireUiTests=true "-Djava.awt.headless=true"
```

Sous PowerShell, mettre les propriétés `-D...` entre guillemets (sinon le point les coupe). La variable
d'environnement `REQUIRE_UI_TESTS=true` et l'ancienne option `-Dcalc.ui.required=true` produisent le même effet que
`-PrequireUiTests=true`. Sous Linux sans écran : `xvfb-run -a ./gradlew check -PrequireUiTests=true`.

## Lire les artefacts

Dans l'onglet **Actions** de GitHub, ouvrir l'exécution puis la section **Artifacts** en bas de page. Il y a un
artefact par système : `test-reports-ubuntu-latest`, `test-reports-windows-latest`, `test-reports-macos-latest`
(conservés 7 jours). Chacun contient :

- `build/reports/tests/test/index.html` : rapport HTML (ouvrir dans un navigateur) ;
- `build/test-results/test/*.xml` : résultats JUnit. Dans `TEST-calc.ui.notebook.NotebookFrameTest.xml` (et les autres fichiers des tests `ui`), l'attribut
  `skipped` doit valoir `0` et `tests` doit valoir le nombre de tests d'interface.

## Protection de la branche `main` (action manuelle)

À activer dans **Settings → Branches → Branch protection rules** (ou **Rulesets**) sur `main` :

- exiger une pull request avant la fusion ;
- exiger que les vérifications d'état réussissent avant la fusion, avec les trois jobs
  `check (ubuntu-latest)`, `check (windows-latest)` et `check (macos-latest)` ;
- exiger que la branche soit à jour avant la fusion.

Ce réglage se fait dans l'interface GitHub ; il n'est pas versionné dans le dépôt. Les jobs n'apparaissent dans la liste
des vérifications sélectionnables qu'après une première exécution du workflow.
