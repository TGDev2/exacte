# Calc

Calc est une calculatrice desktop Java/Swing en français conçue comme un projet portfolio. Elle met l'accent sur la fiabilité de la saisie, la lisibilité du comportement et une séparation claire entre moteur de calcul, logique d'interaction et interface graphique.

## Proposition de valeur

Ce projet ne cherche pas à impressionner par un catalogue d'opérations. Il cherche à montrer qu'un outil simple peut être :

- robuste sur les cas courants et les erreurs utilisateur
- fluide à utiliser au clavier
- structuré de manière lisible et testable
- présentable rapidement à un évaluateur technique

## Ce que l'application fait

- Opérations prises en charge : addition, soustraction, multiplication, division, puissance (exposant entier), racine carrée
- Saisie française souple : virgule ou point décimal, espaces de groupement des milliers (voir « Règles de saisie »)
- Messages d'erreur explicites et ciblés : le focus revient dans le champ fautif
- Historique réutilisable
- Historique persistant entre les sessions, protégé contre la perte de données (voir « Historique persistant »)
- Enchaînement rapide des calculs : après un succès, le résultat revient dans le premier champ pour préparer le calcul suivant
- Politique de précision explicite : le résultat affiché est celui qui sera réutilisé et persisté
- Apparence native : l'application adopte le style du système (Windows, macOS, Linux) et retombe sur l'apparence Java par défaut si elle n'est pas disponible
- Accessibilité : chaque champ est associé à son libellé, tous les composants ont un nom accessible et l'ordre de tabulation suit la lecture (nombres et opération, boutons de calcul, historique et ses boutons)
- Résultat très long : le résultat est tronqué à l'écran sans élargir la fenêtre ; sa valeur complète s'affiche dans l'info-bulle
- Raccourcis utiles :
  - `Entrée` : calculer
  - `Échap` : effacer les champs
  - `Ctrl+R` : reprendre le dernier résultat
  - `Alt` + lettre soulignée : atteindre un champ ou activer un bouton (`Alt+P` premier nombre, `Alt+O` opération, `Alt+S` second nombre, `Alt+C` calculer, `Alt+R` reprendre dernier résultat, `Alt+E` effacer, `Alt+1` / `Alt+2` vers nombre 1 / 2, `Alt+H` effacer l'historique). Sous Windows, les lettres soulignées n'apparaissent qu'après un appui sur `Alt`.

## Architecture

Le projet est volontairement compact, mais les responsabilités sont séparées (sources dans `src/main/java`, tests dans `src/test/java`) :

- `calc/Main.java` : point d'entrée, assemble moteur, persistance, présentateur et fenêtre
- `calc.engine` : moteur de calcul et persistance, sans aucune dépendance à Swing
  - `CalculatorEngine` : validation de la saisie et exécution des calculs
  - `DecimalPolicy` (privé au package) : analyse de la saisie, arrondi, bornes, formatage ; seul endroit où ces règles sont écrites
  - `CalculationLimits` : expose les bornes utiles à l'interface
  - `Operation`, `InputField`, `CalculationRecord`, `CalculationException` (porte le champ fautif)
  - `CalculationHistoryStore`, `FileCalculationHistoryStore`, `HistoryLoadResult`, `CalculationHistoryException` : persistance locale de l'historique
- `calc.ui` : interface
  - `CalculatorPresenter` : logique d'interaction, gestion d'historique, statut, focus et préparation du workflow suivant ; testable sans interface graphique
  - `CalculatorViewModel` (record immuable), `FocusTarget`, `ReuseTarget`, `StatusTone`, `OperationText` (libellés des opérations)
  - `CalculatorFrame` : rendu Swing et branchement des actions utilisateur

Cette séparation permet de tester les parcours d'usage critiques sans dépendre d'une vérification manuelle de l'UI.

## Prérequis

- Un JDK 17 ou plus récent est nécessaire pour lancer Gradle. Le projet est compilé et testé avec Java 21 : si aucun JDK 21 n'est installé, Gradle le télécharge automatiquement (toolchain, résolution via Foojay).
- Gradle n'est pas à installer : le Gradle Wrapper est fourni (`gradlew.bat` sous Windows, `./gradlew` sous Linux et macOS).
- `jpackage` (inclus dans le JDK 21 utilisé par Gradle) n'est nécessaire que pour le packaging Windows ; `package.ps1` le trouve tout seul.

Commandes principales (PowerShell, à la racine du dépôt) :

```powershell
.\gradlew.bat check                              # compile (-Xlint:all -Werror) et exécute les tests JUnit 5
.\gradlew.bat check "-Dcalc.ui.required=true"    # échoue si les tests d'interface ne peuvent pas s'exécuter (comme la CI)
.\gradlew.bat run                                # lance l'application
.\gradlew.bat jar                                # produit build\libs\calc.jar (java -jar build\libs\calc.jar)
.\check.ps1 ; .\run.ps1 ; .\package.ps1          # enveloppes PowerShell qui délèguent à Gradle
```

Sous Linux et macOS, les mêmes commandes s'écrivent `./gradlew check`, `./gradlew run` et `./gradlew jar`.

Les tests portant le tag JUnit `ui` pilotent la vraie fenêtre Swing et nécessitent un affichage. Sans affichage graphique, ils sont ignorés (signalés comme `skipped`), sauf si `-PrequireUiTests=true` est donné à Gradle : ils échouent alors avec un message explicite. Pour forcer le mode sans affichage, `gradlew check -Djava.awt.headless=true` convient : le build transmet explicitement cette propriété à la JVM de test, ce que Gradle ne fait pas de lui-même.

Les scripts PowerShell sont de simples délégations vers Gradle et propagent son code de sortie. Si la politique d'exécution PowerShell bloque les scripts, les lancer avec `powershell -ExecutionPolicy Bypass -File .\run.ps1`.

## Intégration continue

[![CI](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml/badge.svg)](https://github.com/TGDev2/Calculatrice/actions/workflows/ci.yml)

Le workflow `.github/workflows/ci.yml` lance `gradlew check -PrequireUiTests=true` sur Windows, macOS et Linux (JDK 21 Temurin) à chaque `push` sur `main`, à chaque pull request et à la demande. Sous Linux, la commande passe par `xvfb-run` pour disposer d'un affichage virtuel. Grâce à `-PrequireUiTests=true`, aucun test d'interface ne peut être ignoré silencieusement. Les rapports de test sont publiés comme artefacts, et Dependabot met à jour chaque semaine les dépendances Gradle et les actions GitHub.

Détails, reproduction en local et lecture des artefacts : [docs/CI.md](docs/CI.md).
## Vérification

Le projet fournit 187 tests JUnit 5 (`src/test/java/calc/engine` et `calc/ui`) couvrant notamment :

- calculs binaires et unaires valides, avec virgule et point
- erreurs de saisie ciblées, avec le champ fautif et le focus correspondant
- division par zéro, racine d'un nombre négatif, exposants invalides
- règle d'arrondi (cas décimaux sensibles `0,1 + 0,2`, `1 / 3`, petits quotients, racines, puissances négatives, chaînage)
- bornes de taille (saisie de 1005 caractères, résultats de 1000 chiffres, estimation des puissances, absence de gel)
- saisie des milliers (espaces, espaces insécables) et refus des formes ambiguës
- historique : restauration partielle, copies `.bak`, BOM, ancien format sans en-tête, version inconnue non écrasée, mode protégé, écriture atomique et échec d'écriture
- workflow après un calcul réussi, réutilisation d'un résultat d'historique, statuts affichés
- vérification Swing des raccourcis, du changement unaire/binaire, de la réutilisation depuis l'UI et de l'accessibilité (mnémoniques distincts, libellés associés, noms accessibles, ordre de tabulation, info-bulle du résultat long)
- non-régression : 70 cas de référence (`src/test/resources/golden/engine-v1.tsv`) rejoués contre le moteur et le présentateur, sortie par sortie (affichage, valeur réutilisable ou message d'erreur) ; les historiques de référence se trouvent dans `src/test/resources/legacy/v1`

Commande :

```powershell
.\gradlew.bat check
```

Le script `check.ps1` est une enveloppe de cette commande.

## Packaging recommandé pour une évaluation

Commande :

```powershell
.\package.ps1
```

Le script :

- vérifie le projet (`gradlew.bat check`) et construit le JAR
- crée une application Windows portable dans `dist\Calc\Calc.exe`
- crée une archive de distribution dans `dist\Calc-portable.zip`

Ce chemin est le plus adapté à une démonstration portfolio : l'évaluateur peut ouvrir directement l'exécutable packagé sans IDE ni script de compilation. L'application n'est pas signée.

## Historique persistant

L'historique est stocké localement sur le poste utilisateur :

- sous Windows : `%APPDATA%\Calc\history.tsv`
- en repli (variable `APPDATA` absente) : `~\.calc\history.tsv`

**Format.** Fichier texte UTF-8 dont la première ligne est l'en-tête de version `#calc-history v1`, suivie d'une ligne par calcul : quatre colonnes séparées par une tabulation (opération, premier nombre, second nombre, résultat). Un fichier sans en-tête (ancien format) reste lisible et reçoit l'en-tête à la sauvegarde suivante. Un BOM UTF-8 en début de fichier est toléré. Au rechargement, chaque ligne est recalculée par le moteur : la valeur stockée n'est pas crue sur parole.

**Restauration partielle.** Une ligne invalide (nombre de colonnes incorrect, opération inconnue, saisie refusée par le moteur, trop longue) est ignorée et comptée ; les autres lignes sont restaurées. Le statut l'indique (« Historique restauré partiellement : 1 entrée ignorée. ») avec le ton erreur.

**Copies `.bak`.** Chaque fois que le fichier est partiellement lu, illisible ou d'une version inconnue, il est d'abord copié à côté de l'original sous `history.tsv.AAAAMMJJ-HHMMSS.bak` (suffixe `-1`, `-2`… si le nom existe déjà). Une copie existante n'est jamais écrasée ni supprimée automatiquement.

**Mode protégé.** Si le fichier est illisible, s'il porte une version de format inconnue (par exemple `#calc-history v2`), ou si sa copie de sécurité échoue, l'application refuse toute écriture pendant la session (un fichier illisible ou d'une version inconnue ne fournit aucune entrée) : le fichier d'origine n'est jamais remplacé. Les calculs restent possibles ; le statut signale que l'historique n'a pas pu être enregistré.

**Écriture atomique.** Chaque sauvegarde écrit d'abord `history.tsv.tmp` dans le même dossier, force l'écriture sur disque, puis remplace `history.tsv` par un déplacement atomique (remplacement simple si le système de fichiers ne le permet pas). En cas d'échec, le fichier temporaire est supprimé et l'original reste intact. Un historique vide supprime le fichier.

Si le fichier est absent ou invalide, l'application reste utilisable et le statut explique ce qui a été restauré ou non. Les avertissements techniques sont écrits dans la console (`java.util.logging`), pas dans un fichier.

## Précision numérique

La calculatrice applique une règle unique de bout en bout :

- un résultat réussi est formaté, réutilisé pour le calcul suivant et persisté avec la même valeur
- additions, soustractions et multiplications restent exactes sur les décimaux saisis
- un résultat exact n'est jamais arrondi : division terminante (même au-delà de 12 décimales, `1 / 1048576 = 0,00000095367431640625`), racine carrée exacte, puissance exacte
- un résultat approché (division non terminante, racine carrée non exacte, puissance négative non terminante) est arrondi une seule fois avec `HALF_UP` à au moins 12 décimales et au moins 12 chiffres significatifs, c'est-à-dire à `max(12, 11 − e)` décimales où `e` est l'exposant du premier chiffre significatif : `1 / 3 = 0,333333333333`, `√2 = 1,414213562373`, `1 / 3000000000000 = 0,000000000000333333333333` ; un résultat non nul n'est donc jamais affiché `0`
- les puissances acceptent uniquement un exposant entier compris entre `-999` et `999` (`2,0` est accepté comme `2`, `1,5` est refusé) ; `0` à une puissance négative est refusé

Cette politique évite qu'un nombre soit affiché d'une façon, réutilisé avec une autre valeur, puis restauré différemment après relance.

## Bornes de taille

Pour que l'interface ne gèle jamais, les tailles sont bornées avant tout calcul coûteux :

- une saisie (après suppression des espaces de début et de fin) ne dépasse pas 1005 caractères
- tout résultat tient en 1000 chiffres affichables au plus (`max(précision, échelle) ≤ 1000`), sinon le calcul est refusé avec un message
- pour une puissance, la taille est estimée avant le calcul (`max(précision, échelle)` de la base multiplié par `|exposant|`, borné à 1000), exposants négatifs compris : `2 ^ 999` (301 chiffres) est accepté, mais élever ce résultat à la puissance `999` est refusé immédiatement

## Règles de saisie

- la virgule et le point sont tous deux des séparateurs décimaux ; le point n'est jamais un séparateur de milliers : `1.000` vaut `1`
- la notation scientifique n'est pas prise en charge
- un espace, une espace insécable (U+00A0) ou une espace fine insécable (U+202F) peut grouper les milliers de la partie entière, uniquement sous la forme `1 000`, `12 345 678`, `1 000,5` ; `1 00`, `1 0000`, `1,000.5` et `1.000.000` sont refusés
- les espaces en début et fin de saisie sont ignorées
- le résultat est toujours affiché sans groupement, avec une virgule décimale

## Lancer l'application depuis les sources

Commande :

```powershell
.\run.ps1
```

Le script lance `gradlew.bat run` : Gradle compile le projet puis démarre l'application, sans dépendre d'un IDE.

## Pourquoi ce projet va au-delà d'un exercice basique

- Le moteur de calcul est isolé de l'interface.
- La logique d'interaction n'est pas cachée dans la fenêtre Swing.
- Les validations utilisateur sont formulées comme des messages exploitables.
- L'historique sert le workflow, survit à la relance et ne peut pas être détruit par une ligne invalide, une version inconnue ou une coupure pendant l'écriture.
- Les parcours UI visibles sont vérifiés automatiquement, pas seulement la logique métier, sur Linux et Windows en intégration continue.
- Le dépôt permet à un évaluateur de vérifier puis distribuer rapidement une version exécutable.

## Limites connues

- **Instances multiples** : si plusieurs instances de Calc sont ouvertes en même temps, chacune garde son propre historique en mémoire et la dernière à écrire gagne ; il n'y a pas de verrou de fichier.
- **Historique non borné** : le nombre d'entrées n'est pas limité. C'est volontaire : mesuré, 5 000 calculs prennent 3,9 s cumulées et le rechargement 19 ms, ce qui ne justifie pas de limite.
- **Messages d'erreur dans le moteur** : les messages destinés à l'utilisateur sont écrits en français dans le moteur ; il n'y a pas d'internationalisation.
- **Deux opérandes** : le modèle de calcul est « nombre, opération, nombre » ; il n'y a pas de saisie d'expression (parenthèses, priorités).
- **Packaging Windows uniquement** : l'application portable est produite pour Windows et n'est pas signée ; il n'y a ni installeur ni release automatisée. Sous Linux et macOS, l'application se lance avec `java -jar build/libs/calc.jar` après `./gradlew jar`.
- **Arithmétique décimale** : les calculs utilisent des décimaux, sans fractions exactes. Un résultat approché est arrondi puis réutilisé tel qu'affiché, donc un enchaînement peut être légèrement inexact (`1 / 3 = 0,333333333333`, puis `× 3 = 0,999999999999` et non `1`).
- Les tests UI couvrent les parcours critiques, pas un rendu graphique pixel par pixel.
