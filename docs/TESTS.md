# Tests

La commande qui exécute tout est `.\gradlew.bat check`. Ce document explique comment les tests sont rangés, comment en
lancer une partie, comment rejouer un échec trouvé par un test aléatoire et ce que chaque propriété garantit.

## Pyramide des tests

| Niveau | Où | Étiquette (tag) | Rôle |
|---|---|---|---|
| Exemples JUnit 5 | `src/test/java/calc/engine`, `calc/ui`, `calc/number`, `calc/format` | aucune | Un cas précis, un message exact, un comportement décidé. Rapides, faciles à lire. |
| Sorties de référence | `GoldenOutputsTest`, `src/test/resources/golden`, `legacy/v1` | aucune | Rejoue 70 calculs (`engine-v2.tsv`, mêmes entrées que `engine-v1.tsv`) et 11 fichiers d'historique enregistrés : tout changement de sortie est vu. |
| Fenêtre Swing | `CalculatorFrameTest` | `ui` | Raccourcis, focus, accessibilité. Demande un écran (ignoré sans écran, voir `docs/CI.md`). |
| Propriétés | `*Properties` (hors fuzzing) | `property` | Une promesse vraie pour des milliers d'entrées générées, comparée à un oracle indépendant. |
| Robustesse | `*FuzzProperties`, `PresenterStatefulProperties` | `robustness` | Entrées hostiles et séquences d'actions aléatoires : jamais d'exception inattendue, jamais de perte de données. |

Un test d'exemple dit « pour cette entrée, voici la sortie ». Une propriété dit « pour toute entrée de cette forme, ceci
reste vrai » : jqwik génère les entrées (en visant les cas limites), cherche un contre-exemple et le réduit au plus petit
possible). Les propriétés sont écrites sur les API publiques (`CalculatorEngine`, `FileCalculationHistoryStore`,
`CalculatorPresenter`, `ValueFormatter`) ; elles ont survécu au remplacement du moteur en E06, à la révision près des
points listés plus bas (« Révision de l'Epic E06 »).

Mesures sur la machine de développement (Windows 11, JDK 21, démon Gradle chaud, `gradlew cleanTest check`) :

| | Avant E04 | Après E04 | Après E05 | Après E06 |
|---|---|---|---|---|
| Tests exécutés | 249 | 304 (dont 50 propriétés et 4 exemples jqwik) | 437 (dont 75 propriétés et 4 exemples jqwik) | 460 (dont 85 propriétés et 4 exemples jqwik) |
| Essais jqwik | - | 43 405 (42 905 contrôles : le reste est écarté par des hypothèses) | 74 405 (73 736 contrôles), dont 31 000 pour `calc.number` | 83 905 (83 342 contrôles) |
| Durée de `check` | 4,4 s | 19,9 s, 19,9 s et 20,1 s sur trois exécutions | 21,6 s et 21,2 s sur deux exécutions | 28,7 s, 26,8 s et 27,0 s sur trois exécutions |

## Étiquettes

- `property` : propriétés d'exactitude, d'erreurs et de persistance.
- `robustness` : fuzzing de la saisie et du chargeur, test à états du présentateur.
- `ui` : tests Swing.

**Piège : les classes jqwik doivent utiliser `net.jqwik.api.Tag`, pas `org.junit.jupiter.api.Tag`.** jqwik ignore
silencieusement l'annotation de JUnit : la classe disparaîtrait de `-PincludeTags=property` sans aucune erreur.
`PropertyTagsTest` échoue si une classe jqwik n'a pas l'étiquette `property` ou `robustness`, ou utilise la mauvaise annotation.

## Lancer un sous-ensemble

Dans PowerShell, les arguments qui contiennent un point ou une virgule se mettent entre guillemets.

```powershell
.\gradlew.bat check                                            # tout
.\gradlew.bat test "-PincludeTags=property"                    # les propriétés
.\gradlew.bat test "-PincludeTags=property,robustness"         # propriétés et robustesse
.\gradlew.bat test "-PexcludeTags=property,robustness"         # seulement les exemples (rapide)
.\gradlew.bat test --tests "calc.engine.ArithmeticProperties"  # une classe
.\gradlew.bat test --tests "calc.number.*"                     # le noyau numérique exact (E05)
.\gradlew.bat test --tests "calc.format.*"                     # les règles d'affichage (E06)
.\gradlew.bat test --tests "calc.engine.ArithmeticProperties.additionMatchesTheOracle"   # une propriété
.\gradlew.bat check "-Pjqwik.tries.default=20000"              # un essai plus profond (20 fois plus d'entrées)
.\gradlew.bat check "-Pjqwik.reporting.onlyfailures=false"     # rapporter aussi les propriétés réussies (essais, graine)
```

Toute propriété Gradle `-Pjqwik.*` est transmise au JVM de test et l'emporte sur
`src/test/resources/junit-platform.properties`. Quelques propriétés (celles qui écrivent des fichiers, les textes très longs) fixent leur propre nombre d'essais
(200 à 400) ; elles ne suivent pas `jqwik.tries.default`.

## Reproduire un échec à partir d'une graine

Quand une propriété échoue, la console affiche l'échantillon réduit et la graine :

```text
ArithmeticProperties > subtractionMatchesTheOracle STANDARD_OUT
    [property] ArithmeticProperties:subtractionMatchesTheOracle =
      org.opentest4j.AssertionFailedError:
        ',0' SUBTRACTION ',01' ==> expected: <0> but was: <-1>
    ...
    seed = -6375938959018854816   | random seed to reproduce generated values
```

1. **Relancer la même propriété suffit en local.** jqwik garde l'échantillon et la graine de l'échec dans
   `.jqwik-database` (fichier local, ignoré par git) et les rejoue en premier (`jqwik.failures.after.default = SAMPLE_FIRST`).
   Supprimer ce fichier fait oublier les échecs.
2. **Imposer une graine** (par exemple celle lue dans le journal d'une exécution de CI) : ajouter temporairement
   `@Property(seed = "-6375938959018854816")` sur la propriété, puis relancer
   `.\gradlew.bat test --tests "calc.engine.ArithmeticProperties.subtractionMatchesTheOracle"`.
   La graine fixée est signalée par un avertissement (`jqwik.seeds.whenfixed = WARN`) : ne la committez pas.
3. **Pour le test à états**, la graine redonne la même suite de séquences, pas forcément l'échantillon fautif ; la
   console affiche la liste exacte des actions (`Run failed after the following actions`).
4. **Après correction**, ajouter un `@Example` qui reprend l'entrée minimale affichée : c'est la régression durable.

## Générateurs partagés (`src/test/java/calc/testing`)

| Classe | Rôle |
|---|---|
| `DecimalGenerators` | Décimaux valides (signe, virgule ou point, zéros de tête et de fin, jusqu'à 1233 chiffres et 4100 caractères, milliers groupés par espaces insécables, espaces de bord), notation scientifique, fractions `p/q`, zéros, milliers groupés par espaces ordinaires (refusés), textes certainement invalides, textes « vides ». Chaque nombre vient avec la valeur qu'il désigne. |
| `TextGenerators` | Chaînes arbitraires : Unicode complet, caractères de contrôle, chiffres non ASCII, espaces Unicode, quasi-nombres, nombres abîmés, textes très longs. |
| `RecordGenerators` | Calculs (opération et deux textes, décimaux ou fractions) et entrées d'historique produites par le moteur public. |
| `HistoryFileGenerators` | Contenus de fichiers d'historique : lignes valides, rejetées (avec la cause attendue) ou vides, BOM, en-tête, fins de ligne LF, CRLF, CR, versions inconnues, octets aléatoires, octets abîmés. Les fichiers structurés portent ce que le chargeur doit restaurer et rejeter. |
| `PresenterActions`, `PresenterSession` | Séquences d'actions de l'utilisateur et vérification de leurs conséquences. |
| `ArithmeticOracle` | Référence indépendante : analyse du texte, `BigDecimal` à 200 chiffres, texte Auto et texte canonique écrits d'après l'ADR 0004, taille en bits. N'importe rien du code de production. |
| `DisplayTexts` | Relit le nombre d'un texte affiché (« ≈ », « … », `m × 10ⁿ`, `p/q`) sans le code de production. |
| `ResultValues` | Valeur décimale exacte d'un résultat, pour la comparer à l'oracle. |
| `FractionOracle` | Référence indépendante pour les rationnels : paires `BigInteger` jamais réduites, formules d'école, comparaison par produits en croix. N'importe rien du code de production. |
| `RationalGenerators` | Entiers de toutes tailles (avec une part de -3 à 3), fractions brutes (signe au dénominateur, facteur commun), rationnels, intervalles qui contiennent un point connu (exacts, étroits, larges, autour de zéro), littéraux décimaux lus aussi par `BigDecimal`. |
| `MemoryHistoryStore`, `TempDirectories` | Magasin d'historique en mémoire ; répertoires temporaires supprimés après usage. |

## Ce que garantissent les propriétés

**Saisie** (`InputProperties`, `property`)

- Toute saisie valide générée est acceptée, en premier ou second opérande, écrite sous sa forme canonique et affichée par la règle Auto ; le seul refus admis est « le résultat dépasse la taille maximale », qui ne désigne aucun champ.
- La notation scientifique et les fractions sont lues exactement.
- Toutes les écritures de zéro (`0`, `-0,000`, `+.0`) sont acceptées.
- Les saisies de longueur maximale (4100 caractères) sont acceptées.
- Le texte réutilisable d'un résultat, saisi de nouveau, redonne la même valeur et le même texte (un point de l'intervalle pour une valeur approchée).

**Fuzzing de la saisie** (`InputFuzzProperties`, `robustness`)

- Pour toute chaîne (Unicode, contrôles, chiffres non ASCII, espaces Unicode, `null`, de 4200 à 60 000 caractères pour les textes trop longs), en premier, en second ou aux deux endroits : un résultat ou une `CalculationException`, jamais une autre exception, en moins de 100 ms (meilleur de deux essais, après échauffement).
- Les textes qui ne sont pas des nombres sont refusés avec le message « invalide » et le bon champ ; les textes vides avec « Saisissez le … nombre ».

**Arithmétique** (`ArithmeticProperties`, `property`)

- `+`, `-`, `×` égaux à l'oracle (valeur, texte Auto du résultat, texte canonique des opérandes) ; puissances entières positives égales à l'oracle.
- Opérandes jusqu'à la borne : résultat exact, ou refus « dépasse la taille maximale » exactement quand l'oracle compte plus de 4096 bits.
- Commutativité de `+` et `×` (même résultat, ou même refus) ; `(a + b) - b = a` et `(a - b) + b = a`.
- Opérandes groupés par espaces insécables ou entourés d'espaces lus comme les opérandes simples.

**Erreurs** (`ErrorProperties`, `property`)

- Division par zéro (tout zéro écrit), racine d'un négatif, exposant non entier, exposant hors de ±4096, `0` puissance négative, opération absente, saisie vide ou invalide, saisie trop longue, trop de décimales pour le noyau, espace ordinaire entre les milliers, exposant scientifique hors de ±9999, fraction de dénominateur nul : toujours le bon message et le bon champ (premier, second ou aucun) ; le premier opérande est contrôlé avant le second ; le second opérande d'une racine n'est jamais lu.
- L'opérande sans partie entière qui a le plus de décimales acceptées est calculé et son texte canonique se relit (régression du défaut 1 ci-dessous).

**Persistance** (`HistoryRoundTripProperties`, `property` ; `HistoryLoaderFuzzProperties`, `robustness`)

- Sauvegarder puis recharger un historique généré redonne les mêmes entrées (valeur, affichage, valeur réutilisable), sans rejet ni copie, sans fichier temporaire ; le fichier a le format documenté (colonne résultat = texte canonique) ; charger puis sauvegarder ne change pas un octet. Une entrée calculée à partir d'une racine réutilisée se recharge sans rejet, comme le résultat exact de son opérande enregistré.
- Pour un contenu arbitraire : seule exception possible, `CalculationHistoryException` (erreur d'E/S) ; les lignes valides sont restaurées dans l'ordre ; chaque rejet est rapporté avec son numéro de ligne et sa cause ; charger ne modifie jamais le fichier ; une copie identique à l'original existe avant toute écriture dès qu'une ligne est rejetée ; un fichier illisible ou d'une version plus récente n'est jamais écrasé ; sans copie possible, toute écriture est refusée.

**Présentateur** (`PresenterStatefulProperties`, `robustness`) : après chaque action d'une séquence aléatoire (changer d'opération, saisir, calculer, effacer les champs, réutiliser le dernier résultat ou celui qui est sélectionné, sélectionner une entrée, effacer l'historique) :

1. l'historique ne grandit que sur un calcul réussi, d'une entrée, et ne perd des entrées que par l'effacement ;
2. le texte réutilisable de chaque entrée se relit en sa valeur (exactement, ou dans l'intervalle), un décimal exact suit la règle Auto, et le texte réutilisable du dernier calcul est ce qui est placé dans le premier champ ; le résultat attendu d'un calcul est calculé avec un modèle de la mémoire des champs (valeur placée par le présentateur, oubliée dès que le texte change) ;
3. le second champ est actif si et seulement si l'opération est binaire ;
4. l'index sélectionné vaut -1 ou un index valide ;
5. aucune exception ;
6. en plus : l'historique enregistré est celui qui est affiché, le focus ne va jamais sur le champ désactivé, un refus réutilise le message du moteur et cible le champ qu'il désigne.

## Noyau numérique exact (`calc.number`, E05)

Les tests du noyau portent directement sur `Rational` et `CertifiedValue` (voir `docs/adr/0003-representation-numerique.md`). Tout se lance avec `.\gradlew.bat test --tests "calc.number.*"`.

**Rationnels** (`RationalProperties`, `property` ; `RationalTest`, exemples)

- Axiomes de corps : associativité et commutativité de `+` et `×`, distributivité, éléments neutres, opposé, inverse.
- Forme canonique (dénominateur > 0, PGCD = 1, zéro = `0/1`) après chaque opération, y compris quand le générateur donne
  le signe au dénominateur ou un facteur commun.
- `compareTo` cohérent avec `equals` et `hashCode`, et avec l'oracle.
- `+ − × ÷` et puissances entières égales à `FractionOracle` (paires `BigInteger` jamais réduites, formules d'école).
- Lecture des décimaux identique à celle de `BigDecimal` (signe, virgule ou point, zéros, exposant `e`/`E`), et des
  fractions `p/q` de décimaux ; aller-retour texte canonique → lecture, et décimal exact (point ou virgule) → lecture.
- Décimal exact présent si et seulement si le dénominateur réduit est de la forme `2ᵃ5ᵇ`.
- Fuzzing (`RationalParseFuzzProperties`, `robustness`) : pour tout texte, un rationnel canonique dans les limites ou une
  `NumericException` avec un code de lecture, jamais une autre exception.

**Valeurs à intervalle certifié** (`CertifiedValueProperties`, `property` ; `CertifiedValueTest`, exemples)

- **Propriété d'inclusion**, pour `+`, `−`, `×`, `÷`, la puissance entière et la racine : un point `x` dans `X` et `y`
  dans `Y` (intervalles exacts, étroits jusqu'à `2⁻³⁰⁰`, larges, traversant zéro), alors `x op y` (calculé exactement)
  est dans `X op Y`. Chacune de ces six propriétés fait 2000 essais (12 000 au total), quel que soit le réglage par
  défaut. Chaque refus est vérifié contre sa condition (diviseur contenant zéro, racine d'un intervalle qui descend sous
  zéro, puissance négative d'un intervalle contenant zéro).
- Racine certifiée : `lo² ≤ x ≤ hi²`, largeur au plus `2⁻ᵖ × lo` pour toute précision `p` de 1 à 120 bits, exacte si et
  seulement si numérateur et dénominateur sont des carrés parfaits.
- Arrondi extérieur : `bas(x) ≤ x ≤ haut(x)`, au plus 128 bits significatifs, au plus 4096 bits par composante,
  écart relatif d'au plus `2⁻¹²⁶`, idempotent ; refus seulement pour une magnitude d'au moins `2⁴⁰⁹⁵`.
- Toute combinaison de `+ − × ÷` sur des valeurs exactes reste exacte et égale à l'oracle ; une chaîne d'opérations sur
  des intervalles contient toujours la valeur exacte de la même chaîne sur les points.
- Chaque code de `NumericError` est déclenché par au moins un exemple (`everyErrorCodeIsTriggered` échoue si un code
  est ajouté sans déclencheur).

**Limites** (`NumericLimitsTest`, exemples) : les entrées énormes (`2^999` élevé à 999, littéral de 100 000 chiffres,
`1e9999`…) sont refusées avant tout calcul coûteux, et les opérations les plus lentes dans les limites (composantes de
4096 bits) durent moins de 10 ms (meilleur de 8 essais après échauffement). Les mesures détaillées viennent de
`tools/numeric-limits` et figurent dans l'ADR 0003.

## Moteur exact, affichage et réutilisation (E06)

Ces propriétés remplacent `RoundingPolicyProperties` (étiquette `rounding-policy`, supprimée), qui décrivait l'arrondi à
12 décimales de la version 1. Les règles sont celles de `docs/adr/0004-regles-affichage.md`.

**Exactitude** (`ExactnessProperties`, `property`)

- Division exacte : `(a ÷ b) × b = a` exactement, pour tout `a` et tout `b ≠ 0` exact (décimaux et fractions), que le
  quotient soit réutilisé comme valeur ou retapé comme texte canonique.
- Racine : la vraie racine est dans l'intervalle, vérifié exactement (`bas² ≤ x ≤ haut²`) et avec un oracle `BigDecimal` de
  200 chiffres ; les chiffres affichés sont l'arrondi de l'oracle ; la racine d'un carré exact est exacte ; une racine
  approchée a toujours une borne d'erreur et n'est jamais affichée 0.

**Formatage** (`FormatterProperties`, `property`)

- Jamais 0 pour un non-nul : pour toute valeur non nulle générée (rationnels jusqu'à 4000 bits, intervalles qui ne
  contiennent pas 0) et chacun des quatre modes, le texte principal ne se relit pas en 0 ; « ≈ » marque exactement les
  valeurs approchées.
- Aller-retour : le texte canonique d'une valeur exacte tient dans la borne de saisie et se relit exactement ; celui d'une
  valeur approchée se relit en un point de son intervalle.
- Chiffres garantis : tout point de l'intervalle (les deux bornes et le point connu) s'arrondit aux chiffres affichés ;
  seul un intervalle dont les bornes n'ont aucun chiffre commun peut retomber sur un chiffre du centre.

**Réutilisation sans perte** (`LosslessReuseProperties`, `property`) : une suite aléatoire d'au plus 8 opérations exactes
(`+ − × ÷` sur des décimaux et des fractions), enchaînées par le présentateur (résultat repris dans le premier champ, ou
résultat de l'historique placé dans le second), reste exacte à chaque pas et finit sur la valeur de `FractionOracle`.

**Exemples** : `CalculatorDisplayRulesTest` (valeurs de référence, racines près d'un milieu d'arrondi, 3000 divisions et
2000 racines tirées au hasard avec graine fixe), `CalculatorEngineTest` (cas explicites de l'Epic : `1 ÷ 3000000000000`,
`3 ^ -30`, `√2`, `√(9/4)`, `2 ^ 0,5`, espaces insécables, un message par code d'erreur du noyau),
`CalculatorPresenterTest` (`1 ÷ 3` réutilisé puis `× 3` = `1`, valeur mémorisée tant que le texte du champ ne change pas).

### Révision de l'Epic E06

Points du filet de sécurité d'E04 modifiés au changement de moteur, et pourquoi :

- l'oracle (`ArithmeticOracle`) a perdu sa règle d'affichage unique (`display`) au profit du texte Auto et du texte canonique
  de l'ADR 0004 : l'affichage et la valeur réutilisée ne sont plus le même texte ;
- les bornes : 4100 caractères, 4096 bits par composante, exposant ±4096 (les générateurs suivent : `MAX_DIGITS = 1233`) ;
- la grammaire : la notation scientifique n'est plus un texte invalide, l'espace ordinaire n'est plus un séparateur de
  milliers ; le générateur de textes invalides n'insère plus de `e`/`E` (une lettre `e` insérée dans `,000` donne `,0e00`,
  un nombre valide) ;
- le format de l'historique : la colonne résultat est le texte canonique (virgule, fraction) ;
- le présentateur : le modèle de session tient compte de la valeur mémorisée de chaque champ.

## Défauts trouvés par les propriétés

1. **Une entrée valide était perdue au rechargement** (corrigé). Repéré d'abord par un exemple visant le piège noté en E4,
   puis détecté seul par `HistoryRoundTripProperties` une fois le générateur étendu (sans la correction, l'aller-retour échoue).
   Entrée minimale :
   `√` de `,7777…` (1005 caractères, aucune partie entière). Le calcul était accepté et enregistré sous la forme
   canonique `0,7777…` (1006 caractères), puis la ligne était refusée « trop long » au chargement : l'entrée disparaissait
   de l'historique. Correction : le moteur refuse un opérande dont le texte canonique dépasse la borne (voir
   `docs/adr/0002-tests-par-proprietes.md`). Régression : `ErrorProperties.aBareDecimalPointWithTheMostDecimalsIsAcceptedAndReadsBack` (depuis E06, la borne de saisie contient tout texte canonique).
2. **Une exception inattendue au chargement** (`HistoryLoaderFuzzProperties`, corrigé). Entrée minimale : un en-tête
   `#calc-history v2147483648`. `Integer.parseInt` lançait une `NumberFormatException` hors du contrat du chargeur.
   Correction : une version trop grande pour un `int` est une version plus récente (fichier conservé, écriture refusée).
   Régression : `HistoryLoaderFuzzProperties.aVersionTooLargeForAnIntIsJustANewerVersion`.
3. **Une borne hors budget dans une racine approchée** (`CertifiedValueProperties.squareRootIsInclusive`, E05, corrigé
   avant le premier commit ; vert à la première exécution, trouvé à la deuxième graine). Échantillon réduit : le point
   `1/9` dans l'intervalle `[0, 1/9]`, précision 1 bit. La racine `[0, 1/3]` gardait la racine exacte `1/3` d'une borne
   carré parfait dans un résultat approché, alors qu'une borne approchée doit être un nombre binaire d'au plus 128 bits
   significatifs. Défaut réintroduit exprès pour la preuve d'efficacité : la propriété seule le retrouve en 416 essais.
   L'inclusion restait vraie, la borne de taille non. Correction : les deux bornes d'une racine approchée passent par le
   même arrondi extérieur que les autres opérations. Régression : `CertifiedValueTest.squareRootsOfIntervalsAreMonotone`.

4. **Un arrondi en retenue affichait 13 chiffres** (`ArithmeticProperties`, E06, corrigé avant le premier commit).
   Échantillon réduit : `0000000000009999999999999000,0` + `,0`. `9999999999999999` arrondi à 12 chiffres donne
   `1,000000000000… × 10¹⁶` au lieu de `1,00000000000… × 10¹⁶` : le chiffre de trop n'était retiré que pour une échelle
   positive. La même cause donnait `≈ -1,0…` au lieu de `≈ -1…` pour un intervalle très large
   (`FormatterProperties`). Régression : `CalculatorDisplayRulesTest` (`1000000000000000 + 0`) et les deux propriétés.

## Limites connues

- Le budget de 100 ms de `InputFuzzProperties` est une mesure de temps : il prend le meilleur de deux essais et suit un échauffement, mais une machine très chargée peut encore l'invalider.
- Les générateurs choisissent leurs formes de valeurs : les cas non prévus (autres alphabets d'espaces, très gros nombres de lignes d'historique) ne sont pas couverts. `-Pjqwik.tries.default=20000` élargit la recherche sans rien changer au code.
- Le modèle de session du présentateur reproduit la règle de la mémoire des champs : une erreur commune au modèle et au présentateur ne serait pas vue ; `CalculatorPresenterTest` la fixe aussi par des exemples.
- L'oracle n'a qu'une précision de 200 chiffres : les propriétés qui l'utilisent gardent des opérandes assez courts pour que le résultat exact y tienne, et vérifient que c'est bien le cas.
