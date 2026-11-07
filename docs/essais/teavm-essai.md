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
- `Character` (classification Unicode dans `Lexer`) : l'écart annoncé par la décision D2 existe bien. Sur la JVM, U+A7CB et U+2EBF0 donnent `Caractère inattendu`, alors que sous Node ils forment un nom de variable ; U+31EF donne une erreur dans les deux cas, mais avec un message différent. Ces caractères sont absents du golden et du carnet d'essai, donc sans effet sur ce verdict. La correction prévue par D2 appartient à la tâche du corpus de parité.
- `BigInteger.sqrt` : identique à la JVM sur tous les cas rejoués, et `√2` donne la même borne.
- `String.format(Locale.ROOT, "U+%04X")`, `toLowerCase(Locale.ROOT)`, `Enum.valueOf` : aucun écart sur les cas essayés.

## Conséquences sur la construction

- `web/build.gradle.kts` compile avec `-proc:none` en plus de `-Xlint:all -Werror` : le plugin ajoute un processeur d'annotations dont ce module n'a pas l'usage, et que `-Xlint:processing` refuserait.
- `cyclonedxBom` agrège les sous-projets : la tâche `:web:cyclonedxDirectBom` est désactivée dans `build.gradle.kts`. `build/reports/cyclonedx/bom.json` reste identique à celui de `045c91d`, hors numéro de série et horodatage.

## Limites de cet essai

L'essai porte sur Node seul : ni la page ni un navigateur ne sont encore en jeu. Il n'exerce que le golden, le carnet d'essai et les cas listés ci-dessus : la parité systématique entre la JVM et JavaScript reste à établir par le corpus.
