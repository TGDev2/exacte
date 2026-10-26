# Calc

Calc est une calculatrice desktop Java/Swing en français conçue comme un projet portfolio. Elle met l'accent sur la fiabilité de la saisie, la lisibilité du comportement et une séparation claire entre moteur de calcul, logique d'interaction et interface graphique.

## Proposition de valeur

Ce projet ne cherche pas à impressionner par un catalogue d'opérations. Il cherche à montrer qu'un outil simple peut être :

- robuste sur les cas courants et les erreurs utilisateur
- fluide à utiliser au clavier
- structuré de manière lisible et testable
- présentable rapidement à un évaluateur technique

## Ce que l'application fait

- Opérations prises en charge : addition, soustraction, multiplication, division, puissance, racine carrée
- Saisie française souple : virgule ou point acceptés
- Messages d'erreur explicites et ciblés
- Historique réutilisable
- Historique persistant entre les sessions
- Enchaînement rapide des calculs : après un succès, le résultat revient dans le premier champ pour préparer le calcul suivant
- Politique de précision explicite : le résultat affiché est celui qui sera réutilisé et persisté
- Apparence native : l'application adopte le style du système (Windows, macOS, Linux) et retombe sur l'apparence Java par défaut si elle n'est pas disponible
- Accessibilité : chaque champ est associé à son libellé, tous les composants ont un nom accessible et l'ordre de tabulation suit la lecture (nombres et opération, boutons de calcul, historique et ses boutons)
- Résultat très long : le résultat est tronqué à l'écran (`…`) sans élargir la fenêtre ; sa valeur complète s'affiche dans l'info-bulle
- Raccourcis utiles :
  - `Entrée` : calculer
  - `Échap` : effacer les champs
  - `Ctrl+R` : reprendre le dernier résultat
  - `Alt` + lettre soulignée : atteindre un champ ou activer un bouton (`Alt+P` premier nombre, `Alt+O` opération, `Alt+S` second nombre, `Alt+C` calculer, `Alt+R` reprendre dernier résultat, `Alt+E` effacer, `Alt+1` / `Alt+2` vers nombre 1 / 2, `Alt+H` effacer l'historique)

## Architecture

Le projet est volontairement compact, mais les responsabilités sont séparées :

- `calc/Main.java` : point d'entrée, assemble moteur, persistance, présentateur et fenêtre
- `calc/engine/CalculatorEngine.java` : validation et exécution des calculs
- `calc/ui/CalculatorPresenter.java` : logique d'interaction, gestion d'historique, statut, focus et préparation du workflow suivant
- `calc/ui/CalculatorFrame.java` : rendu Swing et branchement des actions utilisateur
- `calc/engine/FileCalculationHistoryStore.java` : persistance locale de l'historique hors de l'interface graphique

Cette séparation permet de tester les parcours d'usage critiques sans dépendre d'une vérification manuelle de l'UI.

## Prérequis

- JDK 17 minimum (le projet est compilé avec `--release 17`).
- Maven n'est pas à installer : le Maven Wrapper est fourni (`mvnw.cmd` sous Windows, `./mvnw` sous Linux et macOS).
- `jpackage` (inclus dans le JDK) n'est nécessaire que pour le packaging Windows.

Commandes principales :

```powershell
.\mvnw.cmd -B verify                 # compile, exécute les tests, produit target\calc.jar
.\mvnw.cmd -B verify "-Dcalc.ui.required=true"   # échoue si les tests d'interface ne peuvent pas s'exécuter (CI)
java -jar target\calc.jar            # lance l'application après verify
```

Sans affichage graphique, les tests d'interface sont ignorés (signalés comme `skipped`), sauf si `calc.ui.required=true` est positionné.

## Vérification

Le projet fournit des tests automatiques couvrant :

- calcul binaire valide avec virgule et point
- calcul unaire valide
- erreurs de saisie ciblées
- division par zéro
- formatage et réutilisation du résultat
- cas décimaux sensibles (`0,1 + 0,2`, `1 / 3`, chaînage après arrondi)
- réutilisation d'un résultat d'historique
- comportement du workflow après un calcul réussi
- restauration de l'historique après relance
- normalisation d'un historique numérique provenant d'une ancienne précision flottante
- dégradation non bloquante si l'historique persistant est invalide
- vérification Swing des raccourcis, du changement unaire/binaire et de la réutilisation depuis l'UI
- accessibilité : mnémoniques distincts, libellés associés à leurs champs, noms accessibles, ordre de tabulation et info-bulle du résultat long

Commande :

```powershell
.\mvnw.cmd -B verify
```

Le script `check.ps1` est une enveloppe de cette commande.

## Packaging recommandé pour une évaluation

Commande :

```powershell
powershell -ExecutionPolicy Bypass -File .\package.ps1
```

Le script :

- vérifie le projet (`mvnw.cmd -B verify`)
- crée une application Windows portable dans `dist\Calc\Calc.exe`
- crée une archive de distribution dans `dist\Calc-portable.zip`

Ce chemin est le plus adapté à une démonstration portfolio : l'évaluateur peut ouvrir directement l'exécutable packagé sans IDE ni script de compilation.

## Lancer l'application depuis les sources

Commande :

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

Le script construit le projet sans les tests (`target\calc.jar`) puis lance l'application, sans dépendre d'un IDE.

## Historique persistant

L'historique est stocké localement sur le poste utilisateur :

- sous Windows : `%APPDATA%\Calc\history.tsv`
- en repli : `~\.calc\history.tsv`

Si ce fichier est absent ou invalide, l'application reste utilisable et le statut explique ce qui a été restauré ou non.

## Précision numérique

La calculatrice applique une règle unique de bout en bout :

- un résultat réussi est formaté, réutilisé pour le calcul suivant et persisté avec la même valeur
- additions, soustractions et multiplications restent exactes sur les décimaux saisis
- un résultat exact n'est jamais arrondi : division terminante (même au-delà de 12 décimales, `1 / 1048576 = 0,00000095367431640625`), racine carrée exacte, puissance exacte
- un résultat approché (division non terminante, racine carrée non exacte, puissance négative non terminante) est arrondi une seule fois avec `HALF_UP` à au moins 12 décimales et au moins 12 chiffres significatifs : `1 / 3 = 0,333333333333`, `1 / 3000000000000 = 0,000000000000333333333333` ; un résultat non nul n'est donc jamais affiché `0`
- les puissances acceptent uniquement un exposant entier compris entre `-999` et `999`
- la saisie attend un décimal simple avec virgule ou point ; la notation scientifique n'est pas prise en charge
- le point est un séparateur décimal, jamais un séparateur de milliers : `1.000` vaut `1`
- un espace, une espace insécable (U+00A0) ou une espace fine insécable (U+202F) peut grouper les milliers de la partie entière, uniquement sous la forme `1 000`, `12 345 678`, `1 000,5` ; `1 00`, `1 0000`, `1,000.5` et `1.000.000` sont refusés ; les espaces en début et fin de saisie sont ignorées ; le résultat est affiché sans groupement

Cette politique évite qu'un nombre soit affiché d'une façon, réutilisé avec une autre valeur, puis restauré différemment après relance.

## Pourquoi ce projet va au-delà d'un exercice basique

- Le moteur de calcul est déjà isolé de l'interface.
- La logique d'interaction n'est plus cachée dans la fenêtre Swing.
- Les validations utilisateur sont formulées comme des messages exploitables.
- L'historique sert le workflow et survit désormais à la relance.
- Les parcours UI visibles sont vérifiés automatiquement, pas seulement la logique métier.
- Le dépôt permet à un évaluateur de vérifier puis distribuer rapidement une version exécutable.

## Limites connues

- L'application reste un outil desktop local, avec une persistance d'historique limitée au poste utilisateur.
- Le périmètre mathématique reste volontairement restreint aux opérations de base, à la racine carrée et aux puissances à exposant entier.
- Les tests UI couvrent les parcours critiques, pas un rendu graphique pixel par pixel.
- Le packaging produit une application portable Windows, pas encore un installeur signé ou une release automatisée.
