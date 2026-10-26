# Tests

La commande qui exécute tout est `.\gradlew.bat check`. Ce document explique comment les tests sont rangés, comment en
lancer une partie, comment rejouer un échec trouvé par un test aléatoire et ce que chaque propriété garantit.

## Pyramide des tests

| Niveau | Où | Étiquette (tag) | Rôle |
|---|---|---|---|
| Exemples JUnit 5 | `src/test/java/calc/engine`, `calc/ui`, `calc/number` | aucune | Un cas précis, un message exact, un comportement décidé. Rapides, faciles à lire. |
| Sorties de référence | `GoldenOutputsTest`, `src/test/resources/golden`, `legacy/v1` | aucune | Rejoue 70 calculs et 11 fichiers d'historique enregistrés : tout changement de sortie est vu. |
| Fenêtre Swing | `CalculatorFrameTest` | `ui` | Raccourcis, focus, accessibilité. Demande un écran (ignoré sans écran, voir `docs/CI.md`). |
| Propriétés | `*Properties` (hors fuzzing) | `property` | Une promesse vraie pour des milliers d'entrées générées, comparée à un oracle indépendant. |
| Robustesse | `*FuzzProperties`, `PresenterStatefulProperties` | `robustness` | Entrées hostiles et séquences d'actions aléatoires : jamais d'exception inattendue, jamais de perte de données. |

Un test d'exemple dit « pour cette entrée, voici la sortie ». Une propriété dit « pour toute entrée de cette forme, ceci
reste vrai » : jqwik génère les entrées (en visant les cas limites), cherche un contre-exemple et le réduit au plus petit
possible. Les propriétés sont écrites sur les API publiques (`CalculatorEngine`, `FileCalculationHistoryStore`,
`CalculatorPresenter`) pour rester valables quand le moteur sera remplacé (E06).

Mesures sur la machine de développement (Windows 11, JDK 21, démon Gradle chaud, `gradlew cleanTest check`) :

| | Avant E04 | Après E04 | Après E05 |
|---|---|---|---|
| Tests exécutés | 249 | 304 (dont 50 propriétés et 4 exemples jqwik) | 437 (dont 75 propriétés et 4 exemples jqwik) |
| Essais jqwik | - | 43 405 (42 905 contrôles : le reste est écarté par des hypothèses) | 74 405 (73 736 contrôles), dont 31 000 pour `calc.number` |
| Durée de `check` | 4,4 s | 19,9 s, 19,9 s et 20,1 s sur trois exécutions | 21,6 s et 21,2 s sur deux exécutions |

## Étiquettes

- `property` : propriétés d'exactitude, d'erreurs et de persistance.
- `robustness` : fuzzing de la saisie et du chargeur, test à états du présentateur.
- `rounding-policy` : en plus de `property`, les propriétés qui dépendent de la politique d'arrondi actuelle (voir plus bas).
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
.\gradlew.bat test "-PincludeTags=rounding-policy"             # celles à réviser en E06
.\gradlew.bat test "-PexcludeTags=property,robustness"         # seulement les exemples (rapide)
.\gradlew.bat test --tests "calc.engine.ArithmeticProperties"  # une classe
.\gradlew.bat test --tests "calc.number.*"                     # le noyau numérique exact (E05)
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
| `DecimalGenerators` | Décimaux valides (signe, virgule ou point, zéros de tête et de fin, longueurs jusqu'à la borne, milliers groupés, espaces de bord), zéros, textes certainement invalides, textes « vides ». Chaque décimal vient avec la valeur qu'il désigne. |
| `TextGenerators` | Chaînes arbitraires : Unicode complet, caractères de contrôle, chiffres non ASCII, espaces Unicode, quasi-nombres, nombres abîmés, textes très longs. |
| `RecordGenerators` | Calculs (opération et deux textes) et entrées d'historique produites par le moteur public. |
| `HistoryFileGenerators` | Contenus de fichiers d'historique : lignes valides, rejetées (avec la cause attendue) ou vides, BOM, en-tête, fins de ligne LF, CRLF, CR, versions inconnues, octets aléatoires, octets abîmés. Les fichiers structurés portent ce que le chargeur doit restaurer et rejeter. |
| `PresenterActions`, `PresenterSession` | Séquences d'actions de l'utilisateur et vérification de leurs conséquences. |
| `ArithmeticOracle` | Référence indépendante : analyse du texte, `BigDecimal` à 200 chiffres, règle d'affichage. N'importe rien du code de production. |
| `FractionOracle` | Référence indépendante pour les rationnels : paires `BigInteger` jamais réduites, formules d'école, comparaison par produits en croix. N'importe rien du code de production. |
| `RationalGenerators` | Entiers de toutes tailles (avec une part de -3 à 3), fractions brutes (signe au dénominateur, facteur commun), rationnels, intervalles qui contiennent un point connu (exacts, étroits, larges, autour de zéro), littéraux décimaux lus aussi par `BigDecimal`. |
| `MemoryHistoryStore`, `TempDirectories` | Magasin d'historique en mémoire ; répertoires temporaires supprimés après usage. |

## Ce que garantissent les propriétés

**Saisie** (`InputProperties`, `property`)

- Toute saisie valide générée est acceptée, en premier ou second opérande, et affichée sous sa forme canonique ; le seul refus admis est « le résultat dépasse 1000 chiffres », qui ne désigne aucun champ.
- Toutes les écritures de zéro (`0`, `-0,000`, `+.0`) sont acceptées.
- Les saisies de longueur maximale sont acceptées.
- Le texte d'un résultat, saisi de nouveau, redonne la même valeur et le même texte.

**Fuzzing de la saisie** (`InputFuzzProperties`, `robustness`)

- Pour toute chaîne (Unicode, contrôles, chiffres non ASCII, espaces Unicode, `null`, jusqu'à 60 000 caractères), en premier, en second ou aux deux endroits : un résultat ou une `CalculationException`, jamais une autre exception, en moins de 100 ms (meilleur de deux essais, après échauffement).
- Les textes qui ne sont pas des nombres sont refusés avec le message « invalide » et le bon champ ; les textes vides avec « Saisissez le … nombre ».

**Arithmétique** (`ArithmeticProperties`, `property`)

- `+`, `-`, `×` égaux à l'oracle (valeur, affichage du résultat et des opérandes) ; puissances entières positives égales à l'oracle.
- Opérandes jusqu'à 1005 caractères : résultat exact, ou refus « dépasse 1000 chiffres » exactement quand l'oracle compte plus de 1000 chiffres.
- Commutativité de `+` et `×` (même résultat, ou même refus) ; `(a + b) - b = a` et `(a - b) + b = a`.
- Opérandes groupés (`1 234 567,5`) ou entourés d'espaces lus comme les opérandes simples.

**Erreurs** (`ErrorProperties`, `property`)

- Division par zéro (tout zéro écrit), racine d'un négatif, exposant non entier, exposant hors de ±999, `0` puissance négative, opération absente, saisie vide ou invalide, saisie trop longue : toujours le bon message et le bon champ (premier, second ou aucun) ; le premier opérande est contrôlé avant le second ; le second opérande d'une racine n'est jamais lu.
- Un opérande de 1005 caractères sans partie entière (`,777…`) est refusé comme trop long (voir « Défauts trouvés »).

**Persistance** (`HistoryRoundTripProperties`, `property` ; `HistoryLoaderFuzzProperties`, `robustness`)

- Sauvegarder puis recharger un historique généré redonne les mêmes entrées (affichage, valeur réutilisable), sans rejet ni copie, sans fichier temporaire ; le fichier a le format documenté ; charger puis sauvegarder ne change pas un octet.
- Pour un contenu arbitraire : seule exception possible, `CalculationHistoryException` (erreur d'E/S) ; les lignes valides sont restaurées dans l'ordre ; chaque rejet est rapporté avec son numéro de ligne et sa cause ; charger ne modifie jamais le fichier ; une copie identique à l'original existe avant toute écriture dès qu'une ligne est rejetée ; un fichier illisible ou d'une version plus récente n'est jamais écrasé ; sans copie possible, toute écriture est refusée.

**Présentateur** (`PresenterStatefulProperties`, `robustness`) : après chaque action d'une séquence aléatoire (changer d'opération, saisir, calculer, effacer les champs, réutiliser le dernier résultat ou celui qui est sélectionné, sélectionner une entrée, effacer l'historique) :

1. l'historique ne grandit que sur un calcul réussi, d'une entrée, et ne perd des entrées que par l'effacement ;
2. la valeur réutilisable de chaque entrée égale le résultat affiché, et le résultat du dernier calcul est ce qui est placé dans le premier champ ;
3. le second champ est actif si et seulement si l'opération est binaire ;
4. l'index sélectionné vaut -1 ou un index valide ;
5. aucune exception ;
6. en plus : l'historique enregistré est celui qui est affiché, le focus ne va jamais sur le champ désactivé, un refus réutilise le message du moteur et cible le champ qu'il désigne.

## Noyau numérique exact (`calc.number`, E05)

Le noyau n'est pas encore branché sur le moteur (E06) : ses tests portent directement sur `Rational` et `CertifiedValue`
(voir `docs/adr/0003-representation-numerique.md`). Tout se lance avec `.\gradlew.bat test --tests "calc.number.*"`.

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

## Propriétés à réviser lors du changement de moteur (E06)

`RoundingPolicyProperties` (étiquette `rounding-policy`) décrit la politique d'arrondi **actuelle** : un quotient ou une
racine non exacts sont arrondis à 12 chiffres significatifs, avec au moins 12 décimales (erreur d'au plus 0,5·10⁻¹²).

- `divisionIsWithinHalfAUnitOfTheTwelfthDecimal` : `|q × b - a| ≤ |b| × 0,5·10⁻¹²`.
- `squareRootIsWithinHalfAUnitOfTheTwelfthDecimal` : `r ≥ 0` et `(r - 0,5·10⁻¹²)² ≤ x ≤ (r + 0,5·10⁻¹²)²` (le carré de gauche est pris à 0 quand `r` est plus petit que la tolérance).
- `exactQuotientsAreNotRounded`, `rootsOfExactSquaresAreNotRounded`, `oneThirdIsRoundedToTwelveDecimals`.

À l'arrivée du moteur exact : réécrire ces bornes selon les garanties du nouveau moteur (intervalles certifiés) ou
supprimer ce qui ne s'applique plus. Toutes les autres propriétés doivent passer sans modification. Points à
surveiller à ce moment-là : le texte exact des messages d'erreur (`ErrorProperties`), la règle d'affichage canonique
(`ArithmeticOracle.display`), les bornes de `CalculationLimits` et le format du fichier d'historique.

## Défauts trouvés par les propriétés

1. **Une entrée valide était perdue au rechargement** (corrigé). Repéré d'abord par un exemple visant le piège noté en E4,
   puis détecté seul par `HistoryRoundTripProperties` une fois le générateur étendu (sans la correction, l'aller-retour échoue).
   Entrée minimale :
   `√` de `,7777…` (1005 caractères, aucune partie entière). Le calcul était accepté et enregistré sous la forme
   canonique `0,7777…` (1006 caractères), puis la ligne était refusée « trop long » au chargement : l'entrée disparaissait
   de l'historique. Correction : le moteur refuse un opérande dont le texte canonique dépasse la borne (voir
   `docs/adr/0002-tests-par-proprietes.md`). Régression : `ErrorProperties.aBareDecimalPointOfTheMaximumLengthIsNoLongerAcceptedThenLost`.
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

## Limites connues

- Le budget de 100 ms de `InputFuzzProperties` est une mesure de temps : il prend le meilleur de deux essais et suit un échauffement, mais une machine très chargée peut encore l'invalider.
- Les générateurs choisissent leurs formes de valeurs : les cas non prévus (autres alphabets d'espaces, très gros nombres de lignes d'historique) ne sont pas couverts. `-Pjqwik.tries.default=20000` élargit la recherche sans rien changer au code.
- L'oracle n'a qu'une précision de 200 chiffres : les propriétés qui l'utilisent gardent des opérandes assez courts pour que le résultat exact y tienne, et vérifient que c'est bien le cas.
