# Calc

Calc est une calculatrice desktop Java/Swing en français conçue comme un projet portfolio. Elle met l'accent sur la fiabilité de la saisie, la lisibilité du comportement et une séparation claire entre moteur de calcul, logique d'interaction et interface graphique.

## Proposition de valeur

Ce projet ne cherche pas à impressionner par un catalogue d'opérations. Il cherche à montrer qu'un outil simple peut être :

- robuste sur les cas courants et les erreurs utilisateur
- fluide à utiliser au clavier
- structuré de manière lisible et testable
- présentable rapidement à un évaluateur technique

## Le carnet de calcul (fenêtre principale)

Depuis l'Epic E12, Calc s'ouvre sur un **carnet de calcul** : on écrit une expression par ligne et le résultat s'affiche à droite, avec le badge « Exact » ou « ≈ Approché » (l'info-bulle donne l'erreur maximale ou la valeur exacte derrière un texte arrondi). Une ligne en erreur affiche son message en toutes lettres sous le champ, la partie fautive est soulignée. Une ligne peut être une expression (`prix * 3`), une affectation (`prix = 12,5`), un commentaire (`# Devis`) ou rien ; `ans` désigne le dernier résultat au-dessus et `$3` le résultat de la ligne 3 (guide : [docs/GUIDE-EXPRESSIONS.md](docs/GUIDE-EXPRESSIONS.md), aussi résumé dans le menu Aide). Modifier une ligne recalcule celles qui suivent ; insérer, supprimer ou déplacer des lignes renumérote les références (`$3` suit sa ligne, vers une ligne supprimée elle devient une erreur explicite).

- **Sauvegarde automatique** : le carnet courant est enregistré après chaque ligne validée (`carnet-courant.calc.json`, voir [docs/FORMAT-FICHIER.md](docs/FORMAT-FICHIER.md)). Un échec d'enregistrement est signalé dans la barre d'état et ne fait perdre aucune ligne.
- **Premier lancement** : l'ancien historique est converti en carnet (une ligne par calcul), après une copie de sécurité ; la barre d'état le dit.
- **Une seule fenêtre écrit** : si Calc est déjà ouvert, la deuxième fenêtre est en lecture seule et l'indique dans la barre d'état.
- **Annuler / rétablir** portent sur les lignes **validées** ; la saisie en cours se retire avec `Échap` (ou avec Annuler, qui l'abandonne d'abord).
- **Aide au premier lancement** : un carnet vide montre trois exemples en filigrane (`prix = 12,5`, `prix * 3`, `ans / 2`), qui ne sont pas enregistrés.
- Le menu **Affichage** (et le sélecteur en haut) change la notation des résultats (Auto, Décimal, Fraction, Scientifique) sans rien recalculer ; le choix est mémorisé.

Raccourcis (le modificateur de menu est `Ctrl` sous Windows et Linux, `Cmd` sous macOS ; la liste est aussi dans le menu Aide > Raccourcis clavier et dans [docs/adr/0010-editeur-carnet.md](docs/adr/0010-editeur-carnet.md)) :

| Touche | Action |
|---|---|
| `Entrée` | valider la ligne et passer à la suivante (après la dernière : nouvelle ligne) |
| `↑` / `↓` | ligne précédente / suivante (la ligne quittée est validée) |
| `Échap` | annuler la saisie en cours dans la ligne |
| `Ctrl+Z` | annuler la dernière modification validée |
| `Ctrl+Y`, `Ctrl+Maj+Z` | rétablir |
| `Ctrl+Entrée` / `Ctrl+Maj+Entrée` | insérer une ligne au-dessous / au-dessus |
| `Ctrl+Maj+K` | supprimer la ligne |
| `Ctrl+Maj+↑` / `Ctrl+Maj+↓` | monter / descendre la ligne |
| `Ctrl+M` | mode d'affichage suivant (toujours `Ctrl`, `Cmd+M` minimise la fenêtre sous macOS) |

### Menu Fichier : documents et exports

Le carnet sans titre est enregistré automatiquement. Le menu **Fichier** permet de garder plusieurs carnets dans des fichiers `.calc.json` (format : [docs/FORMAT-FICHIER.md](docs/FORMAT-FICHIER.md), décisions : [docs/adr/0011-fichiers-dans-l-interface.md](docs/adr/0011-fichiers-dans-l-interface.md)) :

| Entrée | Touche | Effet |
|---|---|---|
| Nouveau | `Ctrl+N` | carnet vide sans titre ; demande d'abord si des modifications seraient perdues |
| Ouvrir… | `Ctrl+O` | ouvre un fichier `.calc.json` ; si l'ouverture échoue, le carnet courant reste tel quel |
| Enregistrer | `Ctrl+S` | écrit le fichier du document (un carnet sans titre demande un nom) |
| Enregistrer sous… | `Ctrl+Maj+S` | demande un nom (`.calc.json` ajouté), confirme avant de remplacer un fichier |
| Exporter en Markdown… / en CSV… | | tableau `n° / Expression / Résultat` dans le mode d'affichage courant ; confirme avant de remplacer un fichier |
| Quitter | | comme le bouton de fermeture de la fenêtre |

- **Titre de la fenêtre** : « Carnet sans titre — Calc » ou « devis.calc.json — Calc » ; un point `•` devant le nom signale un document avec fichier modifié et non enregistré.
- **Confirmations** : Nouveau, Ouvrir et Quitter (fermeture de la fenêtre comprise) proposent « Enregistrer », « Ne pas enregistrer » ou « Annuler » quand un document avec fichier est modifié. Le carnet sans titre est déjà enregistré, il ne pose pas la question, sauf pour Nouveau, qui remplacerait le fichier automatique.
- **Ouverture d'un fichier d'un tiers** : un fichier illisible, invalide ou trop volumineux (plus de 16 Mio) est refusé avec sa raison dans la barre d'état ; un fichier d'une version plus récente de Calc s'ouvre en **lecture seule** (Enregistrer désactivé, Enregistrer sous possible) ; les lignes en erreur sont conservées et comptées (« 2 lignes contiennent une erreur. »).
- **Exemples d'exports** : `Devis cuisine.md` (tableau Markdown) et `Devis cuisine.csv` (séparateur `;`, UTF-8 avec BOM, ouvrable dans Excel ; une cellule qui commencerait par `=`, `+`, `-` ou `@` est préfixée d'une apostrophe).

## Ce que l'application fait

- Un carnet de calcul : une expression par ligne (`+ - * /`, `^`, `√`, parenthèses, variables, `ans`, `$n`), sauvegarde automatique, annuler / rétablir, documents `.calc.json` et exports Markdown et CSV (voir plus haut)
- Calcul exact : fractions exactes, et pour une racine non exacte une valeur approchée avec une borne d'erreur garantie (voir « Précision numérique »)
- Saisie française souple : virgule ou point décimal, notation scientifique `1,5e-7`, espaces insécables de groupement des milliers (voir « Règles de saisie »)
- Messages d'erreur explicites et ciblés : le message est écrit sous la ligne et la partie fautive est soulignée
- Réutilisation sans perte : `ans` et `$n` reprennent la valeur exacte (`1 / 3` puis `ans * 3` donne `1`)
- Apparence native : l'application adopte le style du système (Windows, macOS, Linux) et retombe sur l'apparence Java par défaut si elle n'est pas disponible
- Accessibilité : chaque ligne est un champ accessible dont le nom est « Ligne n » et dont la description donne le résultat, l'ordre de tabulation suit la lecture
- Affichage lisible : 12 chiffres significatifs, « … » quand des chiffres sont omis, « ≈ » pour une valeur approchée
- Exactitude visible : un badge « Exact » ou « ≈ Approché » accompagne le résultat (le texte dit toujours ce que la couleur dit) ; son info-bulle donne « Erreur maximale : ± … » pour une valeur approchée, ou « Valeur exacte : 1/3 (affichage arrondi) » quand le texte est arrondi
- Mode d'affichage : le sélecteur « Affichage » (Auto, Décimal, Fraction, Scientifique) met en forme les résultats sans recalculer ni modifier aucune valeur ; le choix est mémorisé entre deux lancements (préférences Java `java.util.prefs`, nœud `/calc/display`) et, si les préférences sont inaccessibles, l'application démarre en mode Auto
- Raccourcis : voir le tableau du carnet ; `Ctrl+M` passe au mode d'affichage suivant et aucune autre liaison de la fenêtre ni des composants Swing n'utilise cette combinaison (contrôlé par un test avec l'apparence Java et l'apparence Windows ; non contrôlé sous macOS et Linux), et sous macOS elle utilise `Ctrl`, pas `Cmd` : `Cmd+M` (minimiser) n'est pas touché
- L'ancien formulaire à deux champs (deux nombres et une opération) est retiré : chacune de ses fonctions a son équivalent dans le carnet (`ans` pour « Reprendre dernier résultat », `$n` pour « Vers nombre 1 / 2 », `Échap` pour « Effacer », Édition > Tout effacer pour « Effacer historique »), tableau dans [docs/adr/0011-fichiers-dans-l-interface.md](docs/adr/0011-fichiers-dans-l-interface.md)

## Architecture

Le projet est volontairement compact, mais les responsabilités sont séparées (sources dans `src/main/java`, tests dans `src/test/java`) :

- `calc/Main.java` : point d'entrée, ouvre le magasin du carnet (migration de l'ancien historique au premier lancement) et la fenêtre du carnet
- `calc.engine` : moteur de calcul et persistance, sans aucune dépendance à Swing
  - `CalculatorEngine` : exécute les calculs avec le noyau exact et traduit chaque refus en message français ; `Operand` : texte saisi ou valeur déjà connue
  - `OperandParser` (privé au package) : grammaire de saisie (décimal, fraction, notation scientifique, milliers) ; `NumberLiterals` : lecture d'un littéral (milliers en espaces insécables), partagée avec le langage d'expressions ; `NumericMessages` : messages des refus du noyau qui ne nomment aucun champ, partagés aussi
  - `CalculationLimits` : bornes du moteur, reprises de celles du noyau
  - `Operation`, `InputField`, `CalculationRecord` (porte la valeur exacte ou l'intervalle), `CalculationException` (porte le champ fautif)
  - `LegacyRounding` (privé au package) : anciennes règles d'arrondi, utilisées seulement pour vérifier les historiques de la version 1
  - `CalculationHistoryStore`, `HistoryLoadResult`, `CalculationHistoryException` : persistance locale de l'historique
- `calc.number` : noyau numérique exact, sans Swing (voir `docs/adr/0003-representation-numerique.md`)
  - `Rational` : fraction exacte toujours réduite ; `CertifiedValue` : intervalle `[bas, haut]` exact ou approché avec garantie d'inclusion, racine certifiée
  - `NumericLimits` : bornes du noyau ; `NumericException` porte un code `NumericError`, sans texte destiné à l'utilisateur
- `calc.format` : `ValueFormatter`, seul endroit où une valeur devient texte (modes Auto, Décimal, Fraction, Scientifique, texte canonique réutilisable ; voir `docs/adr/0004-regles-affichage.md`)
- `calc.expression` : langage d'expressions du futur carnet, sans interface (voir `docs/adr/0007-grammaire-expressions.md` et le guide `docs/GUIDE-EXPRESSIONS.md`)
  - `Lexer` (jetons et positions), `ExpressionParser` (descente récursive bornée en longueur, profondeur et nombre de nœuds) et `ParsedLine` (nature de la ligne, arbre, commentaire, références et noms utilisés)
  - `Node` : arbre immuable (interface scellée et records) ; `Evaluator` : évaluation avec le noyau exact, noms et références résolus par un `Resolver` de l'appelant (`TableResolver` pour les tests)
  - `CanonicalPrinter` : texte canonique à parenthèses minimales ; `ReferenceRewriter` : renumérotation des `$n` dans le texte d'origine ; `ExpressionException` : code, position et message français
- `calc.notebook` : modèle du carnet (lignes, commandes, annuler / rétablir, évaluation), sans Swing
- `calc.persistence`, `calc.document`, `calc.export` : fichier du carnet (format v3, écriture atomique), documents (nouveau, ouvrir, enregistrer, état modifié, version future en lecture seule) et exports Markdown et CSV ; aucun dialogue ici, les chemins sont des paramètres
- `calc.ui` : interface
  - `StatusTone`, `ResultDescription` : tons de la barre d'état et phrases d'exactitude, partagés
  - `DisplayModeStore` / `PreferencesDisplayModeStore` : mémorisation du mode d'affichage (`java.util.prefs`, erreurs tolérées et journalisées)
  - `calc.ui.widget` : composants Swing réutilisables, sans dépendance à la fenêtre : `DisplayModeSelector` (liste déroulante des modes), `ExactnessBadge` (« Exact » / « ≈ Approché » et son info-bulle), `Palette` et `Contrast` (couleurs et rapport de contraste WCAG)
  - `calc.ui.notebook` : le carnet, fenêtre principale (voir `docs/adr/0010-editeur-carnet.md`)
    - `NotebookPresenter` : toute la logique d'interaction du carnet (validation, navigation, annuler / rétablir, insertion, suppression, déplacement, mode, statut), testable sans interface graphique ; chaque intention renvoie un `NotebookViewModel` immuable (`NotebookRow` par ligne, `NotebookFocus`)
    - `NotebookDialogs` (interface sans Swing : choix de fichier et confirmations) et `SwingNotebookDialogs` (`JFileChooser`, `JOptionPane`) ; les tests en injectent une version scriptée, aucun test n'ouvre une vraie boîte de dialogue ; le présentateur y ajoute Nouveau, Ouvrir, Enregistrer, Enregistrer sous, exports et Quitter (voir `docs/adr/0011-fichiers-dans-l-interface.md`)
    - `NotebookFrame` (fenêtre, menus, barre d'état), `NotebookRowView` (une ligne : numéro, `LineField`, résultat et badge, message d'erreur), `NotebookShortcuts` (les touches, avec le modificateur de menu du système), `NotebookHelp` (textes du menu Aide), `NotebookPalette` (couleurs, contrastes vérifiés)

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

Le projet fournit des tests JUnit 5 et des propriétés jqwik (`src/test/java`, voir [docs/TESTS.md](docs/TESTS.md)) couvrant notamment :

- calculs binaires et unaires valides, avec virgule et point
- erreurs de saisie ciblées, avec le champ fautif et le focus correspondant
- division par zéro, racine d'un nombre négatif, exposants invalides
- exactitude et règles d'affichage (`0,1 + 0,2`, `1 / 3` réutilisé puis `× 3`, petits quotients jamais affichés `0`, racines certifiées, puissances négatives, chaînage)
- bornes de taille (saisie de 4100 caractères, 4096 bits par composante, puissances refusées avant calcul, absence de gel)
- saisie des fractions, de la notation scientifique et des milliers (espaces insécables), refus de l'espace ordinaire et des formes ambiguës
- historique : restauration partielle, copies `.bak`, BOM, ancien format sans en-tête, version inconnue non écrasée, mode protégé, écriture atomique et échec d'écriture
- workflow après un calcul réussi, réutilisation d'un résultat d'historique, statuts affichés
- vérification Swing des raccourcis, du changement unaire/binaire, de la réutilisation depuis l'UI et de l'accessibilité (mnémoniques distincts, libellés associés, noms accessibles, ordre de tabulation, info-bulle du résultat long) ; sélecteur de mode, badge d'exactitude, rendu de l'historique selon le mode, raccourci `Ctrl+M` sans conflit, mode mémorisé
- carnet (E12) : présentateur sans Swing (`a = 2`, `a * 3`, `ans + 1` donnent 2, 6, 7 ; modification de la première ligne, insertion au-dessus d'une ligne référencée, suppression, annuler / rétablir, Échap, modes, migration, lecture seule, échec d'enregistrement simulé), parcours clavier complet dans la vraie fenêtre avec le modificateur de menu de chaque système, badge et info-bulle sur `√2`, message d'erreur sur `1 / 0`, noms accessibles, démarrage sur un répertoire contenant un historique v1, réactivité sur 500 lignes (voir [docs/TESTS.md](docs/TESTS.md))
- accents des textes visibles : un test lit les littéraux des sources qui portent des textes affichés et refuse les formes sans accent connues
- contrastes : chaque couleur de texte de statut et de badge atteint au moins 4,5:1 sur son fond
- non-régression : 70 cas de référence (`src/test/resources/golden/engine-v2.tsv`) rejoués contre le moteur et le présentateur, sortie par sortie (affichage, valeur réutilisable ou message d'erreur) ; chaque écart avec la version 1 (`engine-v1.tsv`) est justifié dans [docs/changements-comportement-E06.md](docs/changements-comportement-E06.md) ; les historiques de référence se trouvent dans `src/test/resources/legacy/v1`
- tests par propriétés et de robustesse (jqwik) : arithmétique comparée à un oracle indépendant, erreurs, aller-retour et fuzzing de l'historique, fuzzing de la saisie, séquences aléatoires d'actions du présentateur, langage d'expressions (aller-retour imprimeur → analyseur, évaluation comparée au noyau, fuzzing, réécriture des références octet pour octet) ; voir [docs/TESTS.md](docs/TESTS.md)

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

> Depuis E11 et E13, cette section décrit l'**ancien historique**, qui n'est plus écrit : il est converti en carnet au premier lancement (et reste lisible pour cette migration). Le fichier courant est désormais `carnet-courant.calc.json`.

L'historique est un fichier JSON versionné, `history.json`, rangé à l'emplacement standard du système (le format complet, avec un exemple, est dans `docs/FORMAT-FICHIER.md`, les décisions dans `docs/adr/0005-format-persistance.md`) :

- Windows : `%APPDATA%\Calc` (à défaut : `<dossier personnel>\AppData\Roaming\Calc`)
- macOS : `~/Library/Application Support/Calc`
- Linux : `$XDG_DATA_HOME/calc`, sinon `~/.local/share/calc`

**Format.** `{"format": "calc-history", "version": 2, "entries": [...]}`. Chaque valeur est un texte exact (`"1/3"`) ; une valeur approchée (une racine) est un intervalle `[lower, upper]` enregistré sans perte, y compris quand elle sert d'opérande. L'opération est un identifiant stable en minuscules (`addition`, `sqrt`...) qui ne dépend pas du code Java. Les champs inconnus sont ignorés, ce qui prépare la version 3.

**Chargement.** Chaque entrée est recalculée par le moteur et comparée au résultat enregistré : une entrée mal formée, incohérente ou refusée par le moteur est écartée et comptée avec sa cause (« 2 calculs restaurés, 1 entrée illisible ignorée. Copie de sauvegarde : … »), jamais affichée. Le contenu de l'historique n'est jamais écrit dans les journaux. Le fichier est borné (8 Mio, 10 000 entrées, 16 384 caractères par chaîne, profondeur 32) : un fichier hostile est refusé en quelques millisecondes et jamais remplacé.

**Migration.** Un `history.tsv` de la version 1 est converti au premier démarrage (« Historique converti au nouveau format (n entrées). ») puis renommé `history.tsv.v1.bak`, sans jamais écraser une sauvegarde existante ; si les deux fichiers existent, le JSON fait foi. Sous macOS et Linux, l'ancien `~/.calc/history.tsv` est repris dans le nouveau dossier.

**Copies `.bak`.** Quand des entrées sont rejetées ou que le fichier est abîmé, il est d'abord copié sous `history.json.AAAAMMJJ-HHMMSS.bak` (suffixe `-1`, `-2`… si le nom existe déjà), jamais écrasé.

**Version plus récente.** Un fichier d'une version future est ouvert en lecture seule quand c'est possible (« Historique créé par une version plus récente de Calc : ouvert en lecture seule. ») et n'est jamais réécrit.

**Deux fenêtres.** Un verrou du système (`history.lock`) protège l'historique : une deuxième instance le charge mais ne l'écrit pas (« Calc est déjà ouvert : l'historique de cette fenêtre ne sera pas enregistré. »). Le verrou est rendu par le système si le programme plante.

**Écriture atomique.** Chaque sauvegarde écrit `history.json.tmp`, force l'écriture sur disque, puis remplace `history.json` par un déplacement atomique ; en cas d'échec l'ancien fichier reste intact.

Si le fichier est absent ou invalide, l'application reste utilisable et le statut explique ce qui a été restauré ou non. Les avertissements techniques sont écrits dans la console (`java.util.logging`), pas dans un fichier.
## Précision numérique

Le moteur calcule avec un noyau exact (`docs/adr/0003-representation-numerique.md`) et affiche selon des règles uniques (`docs/adr/0004-regles-affichage.md`) :

- chaque résultat est exact (fraction exacte : `1 / 3` vaut exactement un tiers), ou approché avec une borne d'erreur garantie : seule une racine carrée non exacte est approchée, et la vraie valeur est certainement dans l'intervalle calculé
- l'affichage montre 12 chiffres significatifs arrondis au plus proche, suivis de « … » si des chiffres sont omis : `1 / 3 = 0,333333333333…` ; hors de `[10⁻⁶, 10¹⁵[`, il passe en notation scientifique : `1 / 3000000000000 = 3,33333333333… × 10⁻¹³` ; une valeur non nulle n'est jamais affichée `0`
- une valeur approchée est précédée de « ≈ » et ne montre que les chiffres garantis : `√2 ≈ 1,41421356237…`, erreur au plus `± 2,1 × 10⁻²⁵` ; `√(9/4) = 1,5` reste exact
- la valeur réutilisée n'est jamais le texte affiché mais le texte canonique, relu exactement : `1/3`, `0,3`, `1e1000` ; tant que le champ n'est pas modifié, la calculatrice réutilise même directement la valeur mémorisée, intervalle compris
- les puissances acceptent uniquement un exposant entier exact compris entre `-4096` et `4096` (`2,0` est accepté comme `2`, `0,5` est refusé) ; `0` à une puissance négative est refusé

## Bornes de taille

Pour que l'interface ne gèle jamais, les tailles sont bornées avant tout calcul coûteux, par les limites du noyau exact :

- une saisie (après suppression des espaces de début et de fin) ne dépasse pas 4100 caractères ; tout texte canonique d'une valeur tient dans cette borne
- un numérateur ou un dénominateur, saisi ou calculé, tient en 4096 bits (environ 1233 chiffres), sinon le calcul est refusé avec un message
- une puissance trop grande est refusée avant le calcul : `2 ^ 999` est accepté, mais élever ce résultat à la puissance `999` est refusé immédiatement
- l'exposant d'une puissance est compris entre `-4096` et `4096`, celui de la notation scientifique entre `-9999` et `9999`

Ces valeurs sont définies dans `NumericLimits` et exposées par `CalculationLimits`. Elles sont justifiées par des mesures (voir `docs/adr/0003-representation-numerique.md`) ; un test garde chaque calcul le plus lourd sous un budget de 50 ms.

## Règles de saisie

- la virgule et le point sont tous deux des séparateurs décimaux ; le point n'est jamais un séparateur de milliers : `1.000` vaut `1`
- une fraction s'écrit `p/q`, sans espace (`3/4`, `-1,5/4`) ; la notation scientifique s'écrit `1,5e-7` ou `3E2`
- une espace insécable (U+00A0) ou une espace fine insécable (U+202F) peut grouper les milliers de la partie entière, uniquement sous la forme `1 000`, `12 345 678`, `1 000,5` ; `1 00`, `1 0000`, `1,000.5` et `1.000.000` sont refusés
- l'espace ordinaire est refusé à l'intérieur d'un nombre, avec un message qui propose l'espace insécable
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
- **Éditeur de carnet** : une ligne de plus de 16 384 caractères est refusée avec un message (la limite du fichier) ; le collage de plusieurs lignes dans un champ les joint en une seule ligne ; les lignes ne sont pas virtualisées (ouverture d'environ 0,85 s pour 500 lignes et 1,5 s pour 5000 ; valider la première ligne de 5000 lignes prend environ 85 à 115 ms) ; pas d'aperçu du résultat pendant la frappe (mesuré possible, écarté : voir l'ADR 0010) ; les raccourcis ne sont pas contrôlés sur un clavier macOS réel ni avec l'apparence Aqua.
- **Packaging Windows uniquement** : l'application portable est produite pour Windows et n'est pas signée ; il n'y a ni installeur ni release automatisée. Sous Linux et macOS, l'application se lance avec `java -jar build/libs/calc.jar` après `./gradlew jar`.
- **Historique et valeurs approchées** : le format de fichier actuel ne stocke pas d'intervalle ; une entrée calculée à partir d'une valeur approchée réutilisée est rechargée comme le résultat exact de son opérande enregistré (un point de l'intervalle affiché). Une puissance d'une telle valeur au-delà d'environ 48 ne peut pas être enregistrée : la ligne est écartée, avec une copie de sauvegarde, au rechargement suivant. Le format de la version suivante stockera les intervalles.
- Les tests UI couvrent les parcours critiques, pas un rendu graphique pixel par pixel.
