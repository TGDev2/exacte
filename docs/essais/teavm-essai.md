# Essai TeaVM : le cœur Java rejoué sous Node

Verdict : GO

Le cœur compile vers JavaScript sans aucune modification, les 70 cas du golden sont identiques et le carnet d'essai est conforme. Deux méthodes du JDK ont dû être complétées, sur les 3 permises.

Essai réalisé le 2026-11-07 sous Windows 11, avec Java 21 et Node v22.14.0. Il se rejoue par `.\gradlew.bat :web:webTest --tests "*GoldenReplayAcceptanceTest"`.

## Résultats

- Version de TeaVM : 0.16.0, plugin Gradle `org.teavm`, cible JavaScript, module UMD, code obfusqué.
- Golden sous Node : `OK 70/70` (55 OK, 15 ERROR), en comparant l'issue, l'affichage, la valeur réutilisable et le message d'erreur.
- Carnet d'essai : OK
- Taille de `calc.js` : 405 712 octets.
- Durée de `webTest` : 6 à 8 s sur 3 passes, dont 4,5 à 4,8 s pour les 37 tests, `calc.js` étant déjà compilé. La compilation par TeaVM ajoute environ 5 s.

Sorties du carnet d'essai sous Node, identiques à celles de la JVM :

| Carnet | Mode | Ligne | Statut | Résultat |
|---|---|---|---|---|
| `(1/3)*3`, `√2`, `2 +` | `AUTO` | 1 | `EXACT` | `1` |
| | | 2 | `APPROXIMATE` | `≈ 1,41421356237…`, borne `± 2,1 × 10⁻²⁵` |
| | | 3 | `ERROR` | `Opérande manquant après « + ».` |
| `10`, `3`, `$1 + 1`, ligne 2 déplacée en 1 | `AUTO` | 3 | `EXACT` | `$2 + 1` vaut `11` ; annuler redonne `10\|3\|$1 + 1`, rétablir `3\|10\|$2 + 1` |
| `1/3`, `√2` | `FRACTION` | 1 | `EXACT` | `1/3` |
| | `SCIENTIFIC` | 2 | `APPROXIMATE` | `≈ 1,41421356237… × 10⁰` |

## Méthodes complétées

| Méthode du JDK | Défaut dans TeaVM 0.16.0 | Complément |
|---|---|---|
| `BigInteger.sqrtAndRemainder` | absente de `TBigInteger`, alors que `sqrt()` y existe | `calc.web.compat.BigIntegerCompat` : `r = x.sqrt()`, reste `x - r²` |
| `Pattern.compile` | présente, mais le motif compilé ne se comporte pas comme sur le JDK (voir plus bas) | `calc.web.compat.PatternCompat` : écrit `\d{3}` sous la forme équivalente `\d\d\d` |

Sans l'extension, `generateJavaScript` échoue et ne signale que `sqrtAndRemainder`, à ses trois sites d'appel de `OutwardRounding`. C'est la seule méthode manquante, chemin du carnet compris. Le défaut de `Pattern.compile` ne se voit pas à la compilation : seul le rejeu du golden l'a révélé.

Mécanisme : une extension TeaVM (`calc.web.compat.CompatPlugin`, un `org.teavm.vm.spi.TeaVMPlugin` déclaré dans `META-INF/services`) ajoute un `ClassHolderTransformer` (`CompatCallRewriter`). Il réécrit les appels à ces deux méthodes vers leurs compléments, dans les classes `calc.*` seulement, au moment où TeaVM les lit. Le bytecode du cœur reste celui de javac. `teavm-core` ne sert qu'à la compilation de `web/`.

## Différence constatée et corrigée

La ligne 63 du golden, `1<U+202F>000<U+202F>000,5`, donnait d'abord une erreur sous Node au lieu de `1000000,5`.

| Motif | Texte | JDK | TeaVM 0.16.0 |
|---|---|---|---|
| `(?:\d{3})+` | `000` | accepté | accepté |
| `(?:\d{3})+` | `000000` | accepté | refusé |
| `(?:\d{2,3})+` | `000000` | accepté | `RangeError`, pile épuisée |
| `(?:\d\d\d)+` | `000000` | accepté | accepté |
| `(?:a{3})+` | `aaaaaa` | accepté | accepté |

Cause, lue dans les sources de TeaVM 0.16.0 : `TPattern.processRangeSet` fait de `\d` et de `[0-9]` un `TSupplRangeSet`, qui n'est pas une feuille. `\d{3}` devient donc un `TCompositeGroupQuantifierSet`, dont le compteur d'entrées, gardé dans l'état de la recherche, n'est pas remis à zéro quand le motif continue vers la suite. Au deuxième passage d'un groupe répété, le compteur vaut déjà le maximum et aucun chiffre n'est lu.

Portée dans le cœur : seul `NumberLiterals.GROUPED_MEMBER` compte une classe de caractères dans un groupe répété. Ce motif sert aux champs du moteur et au langage d'expressions : sans le complément, `1 000 000` (avec espaces insécables) aurait aussi été refusé dans le carnet.

Contre-épreuve, en plus du golden : 33 150 nombres groupés (signes, séparateurs, groupes de mauvaise taille, décimales, exposants, fractions) et 3 001 carnets donnent des sorties identiques à l'octet près entre la JVM et Node. `PatternCompatPropertyTest` compare en outre le complément au JDK sur la JVM.

## Cœur inchangé

Cœur inchangé : git diff --quiet 045c91d -- src/main/java, code 0

## API sensibles observées

- `java.util.regex` : le défaut ci-dessus. Il concerne tout motif futur qui compterait une classe de caractères dans un groupe répété, et `PatternCompat` ne corrige que la forme `\d{3}` : la parité du corpus doit garder ce point sous contrôle. `String.matches`, utilisé par `ExpressionParser`, n'est pas réécrit ; son motif `[0-9]{3}(?:[.,][0-9]*)?` ne compte rien dans une répétition et n'a montré aucun écart.
- `Character` (classification Unicode dans `Lexer`) : l'écart annoncé par la décision D2 existe bien. Sur la JVM, U+A7CB et U+2EBF0 donnent `Caractère inattendu`, alors que sous Node ils forment un nom de variable ; U+31EF donne une erreur dans les deux cas, mais avec un message différent. Ces caractères sont absents du golden et du carnet d'essai, donc sans effet sur ce verdict. La correction prévue par D2 est décrite plus bas, dans « Parité du corpus ».
- `BigInteger.sqrt` : identique à la JVM sur tous les cas rejoués, et `√2` donne la même borne.
- `String.format(Locale.ROOT, "U+%04X")`, `toLowerCase(Locale.ROOT)`, `Enum.valueOf` : aucun écart sur les cas essayés.

## Conséquences sur la construction

- `web/build.gradle.kts` compile avec `-proc:none` en plus de `-Xlint:all -Werror` : le plugin ajoute un processeur d'annotations dont ce module n'a pas l'usage, et que `-Xlint:processing` refuserait.
- `cyclonedxBom` agrège les sous-projets : la tâche `:web:cyclonedxDirectBom` est désactivée dans `build.gradle.kts`. `build/reports/cyclonedx/bom.json` reste identique à celui de `045c91d`, hors numéro de série et horodatage.

## Limites de cet essai

L'essai porte sur Node seul : ni la page ni un navigateur ne sont encore en jeu. Il n'exerce que le golden, le carnet d'essai et les cas listés ci-dessus. La parité systématique entre la JVM et JavaScript est établie par le corpus, dans la section suivante.

## Parité du corpus

Parité Unicode : écart constaté, CharacterCompat

`:web:webTest --tests "*ParityAcceptanceTest"` rejoue le corpus sur la JVM, par `WebNotebook`, et sous Node, par `calc.js` et `web/parity/run-parity.js`, dans les 4 modes. La tâche échoue dès qu'une chaîne diffère, avec la graine, le carnet, le mode et les deux valeurs dans le message. Aucune liste de tolérance n'existe, et aucun cas n'a été retiré du corpus.

Corpus : `export` (19 lignes), 23 entrées `hand-*`, 1000 `line-*` et 50 `notebook-*`, soit 1074 carnets écrits par `ParityCorpusWriter` dans `build/web-parity/corpus.txt`.

| Graine | Carnets | `EXACT` | `APPROXIMATE` | `ERROR` | `COMMENT` | `EMPTY` | Écarts |
|---|---|---|---|---|---|---|---|
| 20261107, valeur par défaut | 1074 | 1168 | 236 | 5776 | 512 | 56 | 0 |
| 10000000000, du même ordre que `github.run_id` | 1074 | 1204 | 184 | 5324 | 480 | 40 | 0 |

Les nombres de résultats comptent les 4 modes. Sans `CharacterCompat`, la graine par défaut donnait ces écarts, dans les 4 modes :

| Carnet | JVM | Node |
|---|---|---|
| `hand-u-a7cb` | `Caractère inattendu « U+A7CB ».` | `Variable inconnue`, suivi du caractère lui-même |
| `hand-u-2ebf0` | `Caractère inattendu « U+2EBF0 ».` | `Variable inconnue`, suivi du caractère lui-même |
| `hand-u-31ef` | `Caractère inattendu « U+31EF ».` | `Caractère inattendu`, suivi du caractère lui-même |
| `notebook-36`, ligne 6 | `Caractère inattendu « U+0984 ».` | ne s'arrête pas sur U+0984, et refuse un caractère plus loin dans la ligne |

Le dernier cas vient d'un carnet tiré au hasard, sur un caractère que personne n'aurait mis dans une liste : `NotebookGenerators.lineTexts` produit de tels caractères, et une autre graine aurait pu bloquer un déploiement sans que personne soit là pour le corriger. L'écart disparaît avec le complément. `calc.web.compat.CharacterCompat` reçoit donc les 7 méthodes à argument `int` qui classent un code point (`getType`, `isLetter`, `isLetterOrDigit`, `isDefined`, `isSpaceChar`, `isWhitespace`, `isISOControl`). Sa table par plages, 4007 entrées et 32 056 octets dans une seule constante, sous la limite de 65 535, est générée depuis le JDK 21 de la chaîne d'outils par `.\gradlew.bat :web:generateCharacterCompat`. `CharacterCompatParityTest` la compare au JDK sur les 0x110000 code points, et `CharacterCompatGeneratorTest` vérifie que le fichier commité est bien la sortie du générateur. Le branchement est celui de `sqrtAndRemainder`, dans `CompatCallRewriter`. Ces 7 méthodes ne comptent pas dans la limite de 3 de la décision D2 : 2 méthodes y restent comptées.

Rien d'autre n'a différé. `RACİNE(4)` avec U+0130, `String.format` de `U+%04X`, le motif de `Rational` et les limites (2^4095, 2^4096, 2^100000, profondeur 100 et 101, 1000 et 1001 caractères, 500 et 501 nœuds) donnent les mêmes chaînes des deux côtés.

Durée de `webTest` complet : 17 s sur 3 passes (16,8 à 17,8 s), `calc.js` étant déjà compilé, dont 2,2 s pour les 24 tests de `ParityAcceptanceTest` et 4,5 s pour les 37 tests du golden qu'elle rejoue. La compilation par TeaVM ajoute environ 5 s. On reste loin de la limite de 5 minutes : la taille du lot n'a pas été réduite.

Taille de `calc.js` avec cette table : 449 659 octets.
