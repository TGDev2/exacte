# Calc

[![CI](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml/badge.svg)](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml)

Calc est une **calculatrice exacte** et un **carnet de calcul** pour le bureau (Java 21, Swing), en français. On écrit une
expression par ligne ; le résultat s'affiche à droite, exact quand il peut l'être, et approché avec une erreur garantie
quand il ne peut pas l'être. C'est un projet portfolio : sa promesse est la fiabilité, et chaque affirmation de ce
document renvoie à un test, à une mesure ou à une limite écrite.

## Fonctionnalités

- **Un carnet** : une expression, une affectation (`prix = 12,5`), un commentaire (`# Devis`) ou rien par ligne ; `ans` (le
  dernier résultat) et `$3` (le résultat de la ligne 3) ; modifier une ligne recalcule celles qui en dépendent ; insérer,
  supprimer ou déplacer des lignes renumérote les références.
- **Des calculs exacts** : `+ - * /`, `^` (exposant entier), `√`, parenthèses. `1 / 3` vaut exactement un tiers et
  `(1 / 3) * 3` redonne exactement `1`. Seule une racine qui ne tombe pas juste est approchée, avec une borne d'erreur.
- **Un affichage lisible** : modes Auto, Décimal, Fraction et Scientifique, sans recalcul ; « ≈ » pour une valeur approchée,
  « … » quand des chiffres sont omis ; un badge « Exact » / « ≈ Approché ».
- **Une saisie française souple** : virgule ou point décimal, notation scientifique (`1,5e-7`), espaces insécables pour
  les milliers.
- **Des erreurs ciblées** : le message est écrit sous la ligne et la partie fautive est soulignée.
- **Des documents** : sauvegarde automatique du carnet, fichiers `.calc.json` (Nouveau, Ouvrir, Enregistrer, Enregistrer
  sous), exports Markdown et CSV.
- **Au clavier** : annuler / rétablir, insérer, déplacer, supprimer une ligne ; raccourcis adaptés à chaque système
  (`Ctrl` ou `Cmd`).

Le détail de l'usage est dans le [guide de l'utilisateur](docs/GUIDE-UTILISATEUR.md), la syntaxe dans le
[guide des expressions](docs/GUIDE-EXPRESSIONS.md).

## Exemples de carnet

```text
# Devis cuisine
plan = 1 250,00          # plan de travail (espace insécable)
meubles = 3 480
pose = 35 * 12,5
total = plan + meubles + pose     # 5 167,5
taux = 0,2
total * (1 + taux)       # TTC : 6 201
ans / 12                 # mensualité sur un an : 516,75
```

```text
1 / 3                    # 0,333333333333…   (Exact : la valeur gardée est 1/3)
ans * 3                  # 1                 (exactement)
√2                       # ≈ 1,41421356237…  (Approché : erreur au plus ± 2,1 × 10⁻²⁵)
√(9/4)                   # 1,5               (une racine exacte reste exacte)
0,1 + 0,2                # 0,3
1 / 0                    # Division par zéro : le diviseur vaut 0.
```

## Téléchargement

Chaque version publiée est une [release GitHub](https://github.com/TGDev2/Calculatrice/releases) (tag `vX.Y.Z`) avec une
application autonome par système, sans Java à installer : `Calc-<version>-windows-x64.zip`,
`Calc-<version>-macos-arm64.tar.gz` (Mac à puce Apple) et `Calc-<version>-linux-x64.tar.gz`. Elle contient aussi
`SHA256SUMS.txt`, la nomenclature logicielle `Calc-<version>-sbom.cdx.json` (CycloneDX) et le
[rapport de conformité](#qualité) `RAPPORT-CONFORMITE.md`.

- **Les applications ne sont pas signées** : Windows SmartScreen et macOS Gatekeeper affichent un avertissement au premier
  lancement. La marche à suivre et la vérification des sommes SHA-256 sont dans [docs/RELEASE.md](docs/RELEASE.md).
- Pas d'installeur : on décompresse l'archive et on lance l'application.
- Aucune version n'a encore été publiée (voir le [journal des modifications](CHANGELOG.md)).

## Démarrer depuis les sources

Il faut un JDK 17 ou plus récent pour lancer Gradle ; le projet est compilé et testé avec Java 21, que Gradle télécharge
au besoin. Gradle lui-même est fourni (Gradle Wrapper).

```powershell
.\gradlew.bat run                                # lance l'application
.\gradlew.bat check                              # compile (-Xlint:all -Werror), exécute les tests, contrôle la couverture
.\gradlew.bat check -PrequireUiTests=true        # échoue si les tests d'interface ne peuvent pas s'exécuter (comme la CI)
.\gradlew.bat conformity                         # check + tests de mutation + rapport de conformité
.\gradlew.bat packageApp -PappVersion=1.0.0      # application autonome archivée dans build\release
```

Sous Linux et macOS : `./gradlew run`, `./gradlew check`, etc. Les scripts `run.ps1`, `check.ps1` et `package.ps1` sont de
simples enveloppes de Gradle. Les tests d'interface demandent un affichage : sans affichage ils sont signalés comme ignorés
(jamais comme réussis), sauf avec `-PrequireUiTests=true`, où ils échouent.

## Qualité

La fiabilité est **mesurée, vérifiée et publiée**, pas seulement affirmée :

- **Tests** : JUnit 5 et propriétés jqwik (des milliers d'entrées générées, comparées à un oracle indépendant), fuzzing de la
  saisie, du langage et des fichiers, séquences aléatoires d'actions. Rangement et commandes : [docs/TESTS.md](docs/TESTS.md).
- **Couverture** (JaCoCo) et **tests de mutation** (PIT, sur le noyau numérique, le formatage, le langage d'expressions, le
  modèle de carnet et la persistance) avec des seuils par package qui font échouer la construction en cas de régression.
  Les mesures et les seuils sont dans [docs/TESTS.md](docs/TESTS.md).
- **Rapport de conformité** : `RAPPORT-CONFORMITE.md`, généré par `gradlew conformityReport` à partir des résultats réels
  (nombre de tests par étiquette, tests ignorés signalés, couverture, mutation, garanties et leurs tests, limites). Il est
  publié comme artefact `conformity-report` de chaque exécution de la [CI](docs/CI.md) et joint à chaque release. Il échoue
  plutôt que de mentir si une donnée manque.
- **Intégration continue** : `gradlew check` sur Windows, macOS et Linux à chaque pull request, tests d'interface obligatoires ;
  un second job vérifie que le JAR est reproductible ([docs/CI.md](docs/CI.md)).

Résumé des garanties (la liste complète, avec les classes de test de chacune, est dans la section « Garanties du produit »
de [docs/TESTS.md](docs/TESTS.md)) :

- les résultats sont exacts, ou encadrés par un intervalle qui contient la vraie valeur ;
- une valeur non nulle n'est jamais affichée `0` ; réutiliser un résultat ne perd rien ;
- toute entrée est bornée avant d'être calculée, et une entrée hostile donne une erreur, pas un gel ;
- un fichier abîmé, hostile ou d'une version plus récente n'est jamais écrasé ; l'écriture est atomique ;
- le carnet enregistré puis relu est identique, et l'ancien historique est migré sans perte.

## Limites connues

- **Applications non signées** et sans installeur ; seul le JAR est reproductible d'une construction à l'autre, pas l'image
  d'application ([docs/RELEASE.md](docs/RELEASE.md)).
- **Release non essayée sur GitHub** : le workflow `release.yml` et le packaging macOS et Linux n'ont pas encore été
  exécutés sur GitHub ; seul Windows a été construit et démarré en local.
- **Clavier macOS non essayé** : les raccourcis sont vérifiés par leur liaison dans la fenêtre (avec `Cmd` simulé), pas sur
  un Mac réel ni avec l'apparence Aqua.
- **Interface** : les tests couvrent les parcours critiques, pas un rendu graphique pixel par pixel. Les lignes ne sont pas
  virtualisées (ouverture d'environ 0,85 s pour 500 lignes, 1,5 s pour 5000, mesuré sur la machine de développement).
- **Calcul synchrone** : le recalcul d'un carnet se fait sur le fil de l'interface ; il est borné par ligne (1000 caractères,
  100 niveaux, 500 éléments, nombres de 4096 bits) et non annulable.
- **Éditeur** : une ligne de plus de 16 384 caractères est refusée ; coller du texte sur plusieurs lignes dans un champ les
  joint en une seule ; un carnet compte au plus 5000 lignes ; pas d'aperçu du résultat pendant la frappe.
- **Langage** : pas de multiplication implicite (`2x`), de pourcentage, de constantes `π` ou `e`, ni de fonctions à plusieurs
  arguments ([docs/GUIDE-EXPRESSIONS.md](docs/GUIDE-EXPRESSIONS.md)).
- **Pas d'internationalisation** : les messages sont écrits en français dans le code.
- **Mutation et couverture** : elles mesurent ce que les tests détectent, pas l'absence de défaut ; les packages de
  l'interface ne sont pas soumis aux tests de mutation et leur couverture dépend de la présence d'un affichage. Les points
  non vérifiés du rapport sont listés dans [docs/TESTS.md](docs/TESTS.md#limites-connues-et-points-non-vérifiés).

## Documentation

| Document | Contenu |
|---|---|
| [docs/GUIDE-UTILISATEUR.md](docs/GUIDE-UTILISATEUR.md) | utiliser le carnet : documents, exports, raccourcis, données, dépannage |
| [docs/GUIDE-EXPRESSIONS.md](docs/GUIDE-EXPRESSIONS.md) | la syntaxe des expressions et les erreurs fréquentes |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | packages, responsabilités, flux d'une ligne, persistance |
| [docs/TESTS.md](docs/TESTS.md) | stratégie de test, garanties, couverture, mutation, rapport de conformité |
| [docs/CI.md](docs/CI.md) | intégration continue et lecture des artefacts |
| [docs/RELEASE.md](docs/RELEASE.md) | publier et télécharger une version, vérifier les sommes, ouvrir une application non signée |
| [docs/FORMAT-FICHIER.md](docs/FORMAT-FICHIER.md) | formats de fichier (carnet v3, anciens historiques) |
| [docs/adr/README.md](docs/adr/README.md) | décisions d'architecture (ADR) |
| [CHANGELOG.md](CHANGELOG.md) | journal des modifications |
