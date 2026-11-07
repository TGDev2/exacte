# Intégration continue

[![CI](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml/badge.svg)](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml)

## Ce que fait la CI

Le workflow [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) s'exécute **à la demande seulement**
(`workflow_dispatch`) : il ne se déclenche ni sur un `push`, ni sur une pull request.

### Pourquoi à la demande

Le dépôt est un dépôt privé : ses minutes gratuites GitHub Actions sont limitées, et une minute Windows compte double
et une minute macOS dix fois. Lancer trois systèmes en continu épuise ce quota, et la CI ne peut alors plus démarrer.
Elle reste donc dans le dépôt, mais c'est une personne qui la lance.

### Ce qui fait foi avant une fusion

La vérification locale : `gradlew.bat check -PrequireUiTests=true`, lancée sur le poste de développement (avec affichage,
donc les tests d'interface s'exécutent). Son résultat est publié comme statut de commit « check » sur le commit à fusionner,
et c'est ce statut que la protection de `main` exige (voir plus bas).

### Lancer la CI sur trois systèmes

- depuis GitHub : onglet **Actions** > **CI** > **Run workflow**, en choisissant la branche ;
- ou en ligne de commande : `gh workflow run ci.yml --ref <branche>`.

À faire avant une publication (sur `main`, avant de poser le tag, voir [RELEASE.md](RELEASE.md)) et après une mise à jour de dépendance (par exemple une pull request de Dependabot), puisque seule la CI exécute le build sous Linux et macOS.


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
si elles diffèrent. Il n'est pas dans la liste des vérifications exigées par la protection de `main` ci-dessous.

Les publications ne passent pas par ce workflow mais par [`release.yml`](../.github/workflows/release.yml) (déclenché par un tag
`vX.Y.Z`, voir [RELEASE.md](RELEASE.md)). Il reprend les mêmes vérifications (`check` avec les tests d'interface obligatoires)
avant de construire les archives.

Les actions sont épinglées par SHA de commit, avec la version en commentaire. Dependabot
([`.github/dependabot.yml`](../.github/dependabot.yml)) propose chaque semaine la mise à jour des dépendances Gradle et
des actions GitHub (5 pull requests ouvertes au maximum par écosystème).

## Le facteur de tolérance des budgets de temps

Les budgets de temps des tests sont des promesses tenues sur la machine de référence, le poste de développement. Les runners de GitHub, partagés, sont plus lents et plus irréguliers : `ci.yml` (jobs `check` et `conformity report`) et `release.yml` (jobs `package` et `conformity report`) y définissent donc `CALC_TIMING_FACTOR: "3"`, un facteur 3 qui multiplie chaque budget mesuré à l'horloge.

Sur le poste de développement, la variable est absente : le facteur vaut 1 et la vérification locale contrôle les budgets de référence. Les budgets ne sont pas relevés, parce qu'une valeur relevée pour la CI ne promettrait plus rien sur la machine de référence. Le détail est dans [TESTS.md](TESTS.md), section « Budgets de temps ».

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
- exiger que les vérifications d'état réussissent avant la fusion, avec la vérification « check »
  (le statut de commit publié après le `gradlew.bat check -PrequireUiTests=true` local), et non plus les trois jobs de la CI ;
- exiger que la branche soit à jour avant la fusion.

Ce réglage se fait dans l'interface GitHub ; il n'est pas versionné dans le dépôt. Le statut « check » n'apparaît dans la liste
des vérifications sélectionnables qu'après sa première publication.

## Le rapport de conformité en CI

Un troisième job, `conformity report`, tourne sous Linux (avec `xvfb`) **à la demande** : il
lance `./gradlew conformity -PrequireUiTests=true -Pjqwik.reporting.onlyfailures=false` (la seconde option fait entrer le nombre d'essais des propriétés dans le rapport), c'est-à-dire `check` (tests et seuils de couverture), les tests de mutation (PIT) puis
`conformityReport`. Les tests de mutation durent plusieurs minutes (mesure et justification dans [TESTS.md](TESTS.md)) ; une pull
request ne lance que `check`, dont les seuils de couverture par package font partie.

Le job publie l'artefact **`conformity-report`** (conservé 30 jours) : `conformite/RAPPORT-CONFORMITE.md` (le rapport), `pitest/` (rapport
HTML des mutants) et `jacoco/` (couverture). Si un seuil de couverture n'est pas tenu, `check` échoue et il n'y a pas de rapport. Si un score de mutation ou une garantie n'est pas tenu, le rapport « NON CONFORME » dit pourquoi et le job échoue. Si une donnée source manque, le job échoue sans rapport.

`release.yml` exécute le même job (`conformity report`, avant la publication) et joint le rapport à la release sous le nom
`Calc-<version>-rapport-conformite.md`, avec sa somme dans `SHA256SUMS.txt`.

## Le déploiement de la page (`pages.yml`)

Le workflow [`.github/workflows/pages.yml`](../.github/workflows/pages.yml) construit la version web, prouve sa parité avec le bureau
et publie la page sur GitHub Pages ([ADR 0015](adr/0015-version-web-teavm.md)). Il est distinct de `ci.yml`, qui ne change pas.

**Déclencheur.** Il part lors d'un `push` sur la branche `main`, ou à la main par `workflow_dispatch`. Le déploiement, lui, n'a lieu que
depuis `main` : lancé depuis une autre branche, le workflow construit et vérifie, sans publier. Un déploiement commencé n'est jamais
interrompu (`cancel-in-progress: false`) : le suivant attend.

**Prérequis, une action manuelle du propriétaire.** Dans l'interface GitHub : Paramètres → Pages → Source : GitHub Actions. Tant que ce
réglage manque, le job `deploy` échoue ; il n'est pas versionné dans le dépôt.

**Les jobs**, tous sous Linux :

1. `conformity` (60 minutes au plus) : `./gradlew conformity -PrequireUiTests=true` sous `xvfb`, avec `CALC_TIMING_FACTOR` à 3 pour
   les budgets de temps. Il publie l'artefact `conformity-report`, dont `RAPPORT-CONFORMITE.md` est à la racine.
2. `site` (30 minutes au plus), après `conformity` : lance `:web:webTest -PrequireBrowser=true -PparitySeed=<github.run_id>` (golden sous
   Node, carnet d'essai, corpus de parité, page dans un navigateur sans affichage), télécharge le rapport, puis assemble le site par
   `:web:site -PconformityReport=conformity/RAPPORT-CONFORMITE.md` et le dépose comme artefact Pages. Un écart de parité fait échouer ce
   job : rien n'est déployé.
3. `deploy` (10 minutes au plus), après `conformity` et `site`, et seulement si la référence est `main` : le seul job qui peut écrire
   sur Pages (`pages: write`, `id-token: write`).

La page est servie à l'adresse https://tgdev2.github.io/Calculatrice/, avec le rapport de conformité à côté (`rapport-conformite.html`).
Les tests de `web/src/webTest` ne passent que par ce workflow : ni `check` ni `ci.yml` ne les lancent, et le rapport de conformité ne les
lit pas (voir [TESTS.md](TESTS.md#la-version-web-g29)).
