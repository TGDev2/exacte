# Tests

La commande qui exécute tout est `.\gradlew.bat check`. Ce document explique comment les tests sont rangés, comment en
lancer une partie, comment rejouer un échec trouvé par un test aléatoire et ce que chaque propriété garantit.

## Pyramide des tests

| Niveau | Où | Étiquette (tag) | Rôle |
|---|---|---|---|
| Exemples JUnit 5 | `src/test/java/calc/engine`, `calc/ui`, `calc/number`, `calc/format`, `calc/expression`, `calc/notebook` | aucune | Un cas précis, un message exact, un comportement décidé. Rapides, faciles à lire. |
| Sorties de référence | `GoldenOutputsTest`, `src/test/resources/golden`, `legacy/v1` | aucune | Rejoue 70 calculs (`engine-v2.tsv`, mêmes entrées que `engine-v1.tsv`) et 11 fichiers d'historique enregistrés : tout changement de sortie est vu. |
| Fenêtre Swing | `NotebookFrameTest`, `NotebookFrameFilesTest`, `NotebookFrameResponsivenessTest`, `MainStartupTest` | `ui` | Raccourcis, menu Fichier, titre, focus, accessibilité. Demande un écran (ignoré sans écran, voir `docs/CI.md`). |
| Propriétés | `*Properties` (hors fuzzing) | `property` | Une promesse vraie pour des milliers d'entrées générées, comparée à un oracle indépendant. |
| Robustesse | `*FuzzProperties` (dont `NotebookFileFuzzProperties`), `NotebookPresenterStatefulProperties`, `NotebookStatefulProperties` | `robustness` | Entrées hostiles et séquences d'actions aléatoires : jamais d'exception inattendue, jamais de perte de données. |

Un test d'exemple dit « pour cette entrée, voici la sortie ». Une propriété dit « pour toute entrée de cette forme, ceci
reste vrai » : jqwik génère les entrées (en visant les cas limites), cherche un contre-exemple et le réduit au plus petit
possible). Les propriétés sont écrites sur les API publiques (`CalculatorEngine`, `JsonHistoryStore`,
`NotebookPresenter`, `ValueFormatter`) ; elles ont survécu au remplacement du moteur en E06, à la révision près des
points listés plus bas (« Révision de l'Epic E06 »).

Mesures sur la machine de développement (Windows 11, JDK 21, démon Gradle chaud, `gradlew cleanTest check`) :

| | Avant E04 | Après E04 | Après E05 | Après E06 |
|---|---|---|---|---|
| Tests exécutés | 249 | 304 (dont 50 propriétés et 4 exemples jqwik) | 437 (dont 75 propriétés et 4 exemples jqwik) | 468 (dont 86 propriétés et 5 exemples jqwik) |
| Essais jqwik | - | 43 405 (42 905 contrôles : le reste est écarté par des hypothèses) | 74 405 (73 736 contrôles), dont 31 000 pour `calc.number` | 84 205 (83 637 contrôles) |
| Durée de `check` | 4,4 s | 19,9 s, 19,9 s et 20,1 s sur trois exécutions | 21,6 s et 21,2 s sur deux exécutions | 29,7 s et 29,1 s sur deux exécutions |

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
| `FakeDialogs`, `NotebookDriver` (`calc.ui.notebook`) | Boîtes de dialogue du menu Fichier jouées par un script (une question non prévue fait échouer le test) ; clavier de l'utilisateur joué sur le présentateur du carnet. |
| `ArithmeticOracle` | Référence indépendante : analyse du texte, `BigDecimal` à 200 chiffres, texte Auto et texte canonique écrits d'après l'ADR 0004, taille en bits. N'importe rien du code de production. |
| `DisplayTexts` | Relit le nombre d'un texte affiché (« ≈ », « … », `m × 10ⁿ`, `p/q`) sans le code de production. |
| `ResultValues` | Valeur décimale exacte d'un résultat, pour la comparer à l'oracle. |
| `FractionOracle` | Référence indépendante pour les rationnels : paires `BigInteger` jamais réduites, formules d'école, comparaison par produits en croix. N'importe rien du code de production. |
| `RationalGenerators` | Entiers de toutes tailles (avec une part de -3 à 3), fractions brutes (signe au dénominateur, facteur commun), rationnels, intervalles qui contiennent un point connu (exacts, étroits, larges, autour de zéro), littéraux décimaux lus aussi par `BigDecimal`. |
| `TempDirectories` | Répertoires temporaires supprimés après usage. |

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

**Persistance** : voir la section « Persistance versionnée (E07) » plus bas (`HistoryRoundTripProperties`, `HistoryJsonFuzzProperties`, `LegacyTsvReaderFuzzProperties`).
**Présentateur de l'ancien formulaire** (`PresenterStatefulProperties`, retiré en E13, remplacé par `NotebookPresenterStatefulProperties` : voir « Fichiers dans l'interface (E13) ») : après chaque action d'une séquence aléatoire (changer d'opération, saisir, calculer, effacer les champs, réutiliser le dernier résultat ou celui qui est sélectionné, sélectionner une entrée, effacer l'historique) :

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

**Exemples** : `ValueFormatterTest` (les quatre modes, les champs du résultat, repli et bascule de notation), `CalculatorDisplayRulesTest` (valeurs de référence, racines près d'un milieu d'arrondi, 3000 divisions et
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

## Persistance versionnée (E07)

Le format est décrit dans `docs/FORMAT-FICHIER.md`, les décisions dans `docs/adr/0005-format-persistance.md`. Tout se lance avec
`.\gradlew.bat test --tests "calc.persistence.*"`. Les tests du package `calc.persistence` remplacent ceux de l'ancien
`FileCalculationHistoryStore` (supprimé) : les mentions de `HistoryLoaderFuzzProperties` et de l'ancien `HistoryRoundTripProperties` dans la
section « Défauts trouvés par les propriétés » désignent les classes d'avant E07, reprises par `LegacyTsvReaderFuzzProperties` et par les
nouvelles propriétés ci-dessous (la régression de l'en-tête `v2147483648` est `LegacyTsvReaderFuzzProperties.aVersionTooLargeForAnIntIsJustANewerVersion`).

Mesures (Windows 11, JDK 21, `gradlew cleanTest check`) : 597 tests, 0 échec, 0 ignoré (468 avant E07), environ 30 s.

| Classe | Étiquette | Ce qu'elle garantit |
|---|---|---|
| `HistoryRoundTripProperties` | `property` | historique généré (moteur public, valeurs de toute taille, racines mémorisées réutilisées) → écriture v2 → lecture : mêmes valeurs exactes **et mêmes intervalles**, même affichage, même texte réutilisable ; le fichier a le format documenté (en-tête, pas de nom de constante Java, aucun nombre JSON sauf `version`, une entrée par ligne) ; lire puis écrire ne change pas un octet ; le lecteur accepte le format écrit indépendamment par les générateurs |
| `HistoryJsonFuzzProperties` | `robustness` | contenu JSON arbitraire (fichiers structurés mêlant entrées valides et abîmées, avec champs inconnus ; octets aléatoires ; texte aléatoire ; fichier tronqué ou abîmé ; JSON quelconque ; imbrications profondes) : seule exception possible `HistoryFormatException`, en moins de 10 s ; les entrées valides sont restaurées dans l'ordre ; chaque rejet porte sa position et sa cause attendue ; charger ne modifie jamais le fichier ; une copie identique existe exactement quand une entrée a été rejetée ; un fichier hors limites n'est jamais remplacé |
| `LegacyTsvReaderFuzzProperties` | `robustness` | même chose pour le lecteur de la version 1 (utilisé par la migration), qui n'écrit rien ; un en-tête de version supérieure à 1 ne lit et ne rejette rien |
| `LegacyMigrationTest` | - | migration de **chacun des 11 fichiers** de `legacy/v1` : entrées et valeurs exactes attendues, sauvegarde `history.tsv.v1.bak` identique octet pour octet, fichier v1 retiré, aucun nom Java ni flottant dans le JSON ; idempotence (relancer ne change aucun octet) ; le fichier de version future n'est ni lu ni migré ; les deux fichiers présents → le JSON fait foi ; une sauvegarde existante n'est jamais écrasée ; échec d'écriture → v1 intact puis conversion au démarrage suivant ; reprise de `~/.calc` simulée |
| `HostileFilesTest` | - | fichiers hostiles (un million de `[`, 500 000 objets imbriqués, chaîne ou clé de 6 Mo, nombre de 3 millions de chiffres, exposant de 3 millions de chiffres, 10 001 entrées, fichier d'un octet au-dessus de la limite, fichier de 100 Mo, un million de clés égales, valeurs `1e999999999`...) : refus avec la bonne cause, au plus 2 ms mesurées (meilleur de trois), budget d'assertion de 2 s ; le magasin ne les remplace jamais ; plus gros historique que l'application écrit : 10 000 entrées usuelles en 114 ms, 8 Mio d'entrées de 600 chiffres en 914 ms |
| `JsonHistoryStoreTest` | - | forme documentée du fichier ; pas de fichier temporaire ; **échec d'écriture simulé** (déplacement qui échoue, nom temporaire occupé) : le v2 précédent reste intact ; version 99 : lecture seule, **jamais réécrite même après un calcul et un effacement**, pas de copie ; version future illisible : rien chargé, rien écrit ; champs inconnus ignorés ; limites d'écriture (entrées, taille) ; fichier abîmé copié puis remplaçable |
| `StoreLockTest` | - | deux magasins sur le même dossier : le second est en lecture seule, charge, n'écrit rien (pas de migration, pas de copie) ; la fermeture rend le verrou ; **un processus qui prend le verrou puis est tué** (`destroyForcibly`) : le verrou est repris par le système, 55 ms après la mort du processus sous Windows |
| `DataDirectoryTest` | - | Windows (avec et sans `APPDATA`), macOS, Linux (`XDG_DATA_HOME` absolu, absent, vide, relatif), autres systèmes, ancien dossier `~/.calc` : environnement simulé, rien ne lit le vrai |
| `JsonParserTest`, `OperationIdsTest` | - | lecteur JSON strict (refus de tout ce qui n'est pas du JSON valide, limites exactes de profondeur, chaîne et nombre, aucune récursion au-delà de 32 niveaux même sur deux millions d'ouvertures) ; table des identifiants égale à la table documentée, en minuscules, distincts |
| `CalculatorHistory*Test` | - | statuts du présentateur : migration (« Historique converti au nouveau format (n entrées). »), rejets (« entrée(s) »), version plus récente, instance déjà ouverte, calcul en lecture seule ; les 11 fichiers de référence ouverts par la fenêtre ; journal (chemins, numéros d'entrée, jamais le contenu) |

Générateur : `JsonHistoryGenerators` écrit le JSON à la main, avec sa propre table d'identifiants, indépendamment du code de production
(c'est un deuxième énoncé du format).
## Interface : modes, exactitude visible, textes et accessibilité (E08)

Pas de propriété ici : des exemples, tous rapides.

- `CalculatorDisplayModeTest` (présentateur, sans fenêtre) : changer de mode reformate le résultat et l'historique sans
  recalculer (les entrées sont les mêmes objets, aucune sauvegarde de l'historique), le mode est mémorisé, `√2` est
  approché avec sa borne, `1 ÷ 4` exact sans fraction, `1 ÷ 3` exact avec sa fraction secondaire, un magasin de
  préférences qui échoue donne le mode Auto.
- `PreferencesDisplayModeStoreTest` : lecture et écriture sur un nœud de préférences isolé (nom aléatoire, supprimé après
  chaque test), valeur inconnue, absence de nœud, nœud dont tous les accès sont refusés.
- `widget/WidgetsTest`, `widget/HistoryLineTest` : composants réutilisables seuls (sélecteur, badge, rendu de ligne).
- `widget/PaletteContrastTest` : rapport de contraste WCAG de chaque couleur de texte sur son fond (≥ 4,5:1) ; la sortie
  du test donne les rapports.
- `VisibleTextsTest` : les littéraux des sources qui portent des textes affichés ne contiennent aucune forme sans accent
  connue ; vérifié en retirant un accent à la main (le test échoue).
- `CalculatorFrameTest` (tag `ui`, retiré en E13 : voir `NotebookFrameTest`) : sélecteur, badge, fraction secondaire, rendu de l'historique, `Ctrl+M` (et absence de
  liaison identique dans les tables d'entrées des composants), mode mémorisé, noms accessibles, ordre de tabulation. Les
  tests `ui` ont aussi été lancés avec l'apparence Windows (`-Dswing.defaultlaf=...WindowsLookAndFeel` dans
  `JAVA_TOOL_OPTIONS`) : même résultat.

## Langage d'expressions (E09)

La grammaire et les décisions sont dans `docs/adr/0007-grammaire-expressions.md`, le guide utilisateur dans
`docs/GUIDE-EXPRESSIONS.md`. Tout se lance avec `.\gradlew.bat test --tests "calc.expression.*"`.

Mesures (Windows 11, JDK 21, `gradlew cleanTest check`) : 832 tests, 0 échec, 0 ignoré (628 avant E09, dont 204 pour
`calc.expression`), de 33 à 35 s.

| Classe | Étiquette | Ce qu'elle garantit |
|---|---|---|
| `ExpressionEvaluationTest` | - | tables de valeurs exactes : priorités et associativité (`2+3*4 = 14`, `-2^2 = -4`, `2^3^2 = 512`, `2^-1 = 1/2`, `(1/3)*3 = 1`, `--2 = 2`...), racines (`√2*√2` approché qui contient 2), virgule et point, notation `e`, espaces insécables, opérateurs Unicode, mots réservés dans toutes les casses, variables sensibles à la casse, références `ans` et `$n`, commentaires, nature de la ligne, références et noms relevés |
| `ExpressionErrorsTest` | - | **chaque message** du langage avec son code et sa position exacte (début inclus, fin exclue) : erreurs lexicales, syntaxiques et d'évaluation, longueur, profondeur et nombre de nœuds aux bornes exactes, caractère invisible nommé par son code, caractère hors du plan de base |
| `LexerTest`, `CanonicalPrinterTest`, `ReferenceRewriterTest` | - | jetons et positions ; textes canoniques (parenthèses minimales) ; renumérotation de l'exemple de l'Epic (3 → 4, 5 supprimée), lignes invalides réécrites quand même |
| `ExpressionRobustnessTest` | - | 10 000 parenthèses imbriquées : « Trop d'imbrications » par le contrôle de profondeur (analyseur sans limite de longueur) et ligne trop longue avec les limites par défaut ; chaque construction imbriquée comptée ; ligne de 1 Mo refusée en une dizaine de microsecondes ; analyse à la profondeur maximale et arbres de 100 000 niveaux évalués et imprimés sur une pile de 512 Ko, sans `StackOverflowError` |
| `ExpressionGuideTest` | - | les exemples du guide s'évaluent ligne à ligne avec les valeurs annoncées ; chaque ligne du tableau « Erreurs fréquentes » donne exactement son message |
| `ExpressionRoundTripProperties` | `property` | arbre aléatoire (toutes formes, nombres de toutes tailles, noms Unicode, `ans`, `$n`, affectations) → imprimeur canonique → analyseur → même arbre, positions mises à part ; le texte canonique est un point fixe ; le texte désigné par la position de **chaque** nœud, relu seul, redonne ce nœud |
| `ExpressionDifferentialProperties` | `property` | arbre aléatoire de nombres : l'évaluateur donne la même valeur, ou le même code de refus, que les opérations du noyau appliquées directement, sur l'arbre et sur l'arbre réimprimé puis relu (couverture vérifiée : environ 59 % exacts, 14 % approchés, 27 % refusés) |
| `ExpressionFuzzProperties` | `robustness` | toute ligne (Unicode, contrôles, morceaux du langage collés ou espacés, lignes longues et très longues, arbres imprimés) : un résultat ou une `ExpressionException` avec un message et une position dans la ligne, jamais une autre exception, en moins de 50 ms (meilleur de deux après échauffement) |
| `ReferenceRewriteProperties` | `property` | renuméroter puis relire donne les références renumérotées (ou « Référence vers une ligne supprimée » à la position du premier `$?`) et tout le texte hors des jetons de référence est identique octet pour octet ; le texte attendu est construit à partir des jetons du lexer ; sur un texte quelconque, seules les suites `$chiffres` avant le premier `#` changent (oracle par expression régulière) |

**Preuve d'efficacité** : la priorité du moins unaire et de `^` a été inversée temporairement dans l'analyseur
(`-2^2` lu `(-2)^2`) : 8 tests échouent, dont trois propriétés à elles seules (`printedTreesParseBackToTheSameTree`,
`theCanonicalTextIsAFixedPoint`, `theEvaluatorMatchesTheCoreAppliedDirectly`) ; la modification a été retirée sans être
commitée.

## Modèle de carnet (E10)

La sémantique est dans `docs/adr/0008-semantique-carnet.md`. Tout se lance avec `.\gradlew.bat test --tests "calc.notebook.*"`.

Mesures (Windows 11, JDK 21, `gradlew cleanTest check`, trois exécutions avec de nouvelles graines après la revue) : 875 tests, 0 échec,
0 ignoré (832 avant E10, dont 43 pour `calc.notebook`), de 37 à 38 s.

| Classe | Étiquette | Ce qu'elle garantit |
|---|---|---|
| `NotebookSemanticsTest` | - | `ans` enchaîné et qui saute les lignes vides, commentaires et lignes en erreur ; `$n` valide, vers la ligne elle-même, vers le bas ou au-delà de la fin (erreur), vers une ligne vide, un commentaire, une ligne en erreur ; variables visibles sous leur définition, masquage, nom inconnu, mot réservé, affectation en erreur qui définit quand même son nom ; propagation « Dépend de la ligne n » en chaîne ; chaque message avec son code et sa position exacte |
| `NotebookEditingTest` | - | modifier une ligne haute recalcule les lignes qui en dépendent ; insérer au-dessus d'une ligne référencée réécrit `$3` en `$4` avec la même valeur ; supprimer donne `$?` et une erreur explicite ; déplacer la cible en dessous rend la référence en erreur sans changer sa cible, la redéplacer la rétablit ; référence au-delà de la fin qui y reste ; tout effacer puis annuler ; annuler / rétablir, commande sans effet non enregistrée, pile bornée à 200 ; 5000 lignes au plus, commande refusée sans effet ; identifiants stables jamais réutilisés ; évaluation incrémentale égale à l'évaluation complète |
| `NotebookStatefulProperties` | `robustness` | 300 séquences d'au plus 40 commandes (modifier, insérer avant ou après, ajouter, supprimer, déplacer, tout effacer, annuler, rétablir) sur des lignes qui se référencent ; après **chaque** pas : (1) chaque référence désigne la ligne (même identifiant) qu'elle désignait à sa saisie, ou est explicitement en erreur (`$?`, numéro au-delà de la fin) ; (2) aucune référence valide ne pointe vers le bas, et une ligne qui a une valeur utilise exactement les lignes rattachées au-dessus ; (3) deux évaluations du même carnet sont égales, et l'évaluation incrémentale de l'éditeur égale une évaluation complète ; (4) annuler puis rétablir redonne un carnet et des résultats égaux ; (5) tout annuler redonne le carnet initial. En plus : les textes seuls redonnent les mêmes résultats (ce qu'E11 enregistre), et la valeur de toute ligne réduite à `$n`, `ans` ou un nom est recalculée d'après le rattachement et les textes au-dessus, sans le résolveur. Le rattachement attendu est tenu par le test lui-même, avec sa propre lecture des références. Couverture mesurée puis **imposée** (`Statistics.coverage`, à environ la moitié de la mesure) : référence renumérotée dans 75 % des séquences, supprimée dans 51 %, vers le bas dans 94 %, ligne réduite à une référence ou un nom dans 84 % |
| `HistoryBridgeProperties`, `HistoryBridgeTest` | `property` | toute entrée d'historique produite par le moteur public (opérandes de toute taille, fractions, négatifs, racines) donne le texte documenté (`1,5 + 2,5`, `2 ^ (-3)`, `(1/3) * 3`, `√(9)`), qui s'évalue exactement à la même valeur, ou au même intervalle ; seules les entrées qu'aucune ligne ne peut écrire n'en donnent pas : opérande plus long qu'une ligne (3 % des entrées générées), opérande approché (racine mémorisée) |
| `NotebookPerformanceTest` | - | 1000 lignes avec références, variables, `ans` et commentaires : la modification de la ligne 1 recalcule tout le carnet en moins de 200 ms (meilleur de 10 après échauffement). Mesuré sur cinq exécutions complètes : 9,4 à 9,9 ms avec des rationnels exacts (médianes 13,5 à 14,2 ms, pire 20 ms), 4,5 à 4,6 ms avec des intervalles (médianes 4,8 à 4,9 ms) ; annuler recalcule en 4 à 14 ms ; aucune optimisation par graphe n'est nécessaire |
| `NotebookDependenciesTest` | - | le package n'importe que `java.util`, le langage, le noyau numérique et trois classes du moteur : ni Swing, ni AWT, ni persistance |

**Preuve d'efficacité** : la réécriture des références a été retirée temporairement de l'insertion (`insertAt` construisait
le nouveau carnet sans renuméroter) : 3 tests échouent, dont `NotebookStatefulProperties` à lui seul, réduit à une seule
action (`insertAfter` d'une ligne `x = $11`) ; la modification a été retirée sans être commitée.

## Persistance du carnet, documents et exports (E11)

Le format est décrit dans `docs/FORMAT-FICHIER.md` (partie « Format du carnet »), les décisions dans `docs/adr/0009-documents-et-exports.md`. Tout se lance avec
`.\gradlew.bat test --tests "calc.persistence.Notebook*" --tests "calc.document.*" --tests "calc.export.*"`.

Mesures (Windows 11, JDK 21, `gradlew cleanTest check`, trois exécutions avec de nouvelles graines) : 1 032 tests, 0 échec, 0 ignoré (875 avant E11, soit 157 nouveaux), de 44 à 45 s.

| Classe | Étiquette | Ce qu'elle garantit |
|---|---|---|
| `NotebookCodecTest` (23) | - | l'exemple du format est exactement ce qui est écrit ; **aucun résultat** dans le fichier ; mode d'affichage (absent ou inconnu : auto) ; champs inconnus ignorés à tous les niveaux ; **une ligne que le langage refuse est conservée telle quelle** et apparaît en erreur ; identifiants absents, mal formés ou en double régénérés de façon déterministe (premier porteur gardé, suite après le plus grand) ; sauts de ligne remplacés sans couper la ligne ; substitut isolé conservé ; lire puis écrire ne change aucun octet ; refus clairs (`calc-history`, JSON invalide, doublon de clé, UTF-8 invalide, version absente ou inférieure à 3, `lines` mal formé) ; version supérieure lue au mieux, jamais refusée pour son contenu ; limites exactes (5000 lignes et 5001, 16 384 caractères et 16 385, 100 001 valeurs, fichier d'un octet au-dessus) ; un carnet qui ne peut pas être relu n'est pas écrit |
| `NotebookRoundTripProperties` (2, 300 et 100 essais) | `property` | carnet généré, puis **modifié** (insertions, suppressions, déplacements : identifiants non contigus) → écriture v3 → lecture : mêmes textes, mêmes identifiants, même mode, **mêmes résultats après le recalcul** ; lire puis écrire ne change aucun octet ; le fichier est de l'UTF-8 valide sans `value`, `exact` ni `result` |
| `NotebookFileFuzzProperties` (6, 300 à 500 essais) | `robustness` | fichiers structurés avec champs inconnus et textes de ligne quelconques (accents, guillemets, contrôles, 1200 caractères) : **toutes les lignes sont gardées, dans l'ordre** ; un élément qui n'est pas une ligne donne `MALFORMED_STRUCTURE` et rien d'autre ; octets quelconques, fichier tronqué, octet changé, plage supprimée, objet répété, JSON quelconque, imbrications de 33 à un million de niveaux : seule exception possible `NotebookFormatException`, en moins de 10 s ; ce qui est chargé se recalcule sans exception |
| `NotebookHostileFilesTest` (19) | - | fichiers hostiles refusés avec la bonne cause, meilleur de trois (tableau ci-dessous) ; fichier de 100 Mo refusé sur sa taille seule ; seul un fichier ordinaire est lu ; recalcul **borné** de 5000 lignes aussi lourdes que le langage le permet (tableau ci-dessous) |
| `NotebookStoreTest` (14) | - | carnet courant atomique (un fichier, pas de temporaire) ; **seconde instance en lecture seule** : charge, n'écrit pas un octet, état exposé ; verrou partagé avec `JsonHistoryStore` dans les deux sens ; **version supérieure : lecture seule, jamais réécrite** (ni après une saisie, ni après « tout effacer »), pas de copie ; version illisible : rien affiché ni écrit ; fichier abîmé copié puis remplaçable ; copie impossible, hors limites ou dossier à la place du fichier : jamais remplacé ; **échec d'écriture simulé** : l'ancien carnet reste intact, pas de temporaire ; carnet hors format : phrase en français, fichier précédent intact ; carnet maximal (5000 lignes) relu |
| `NotebookMigrationTest` (34) | - | **les 10 fichiers `legacy/v1` lisibles passent la chaîne v1 → v2 → v3** (lignes attendues, valeurs exactes recalculées, phrase d'état, `history.tsv.v1.bak` identique octet pour octet, `history.json.v2.bak` identique à `history.json`, aucun autre fichier) ; `future-version.tsv` ni lu ni converti ; **idempotence** : relancer ne crée, ne supprime ni ne modifie aucun fichier ; reprise depuis `~/.calc` ; historique v2 converti dans l'ordre ; entrée sans ligne possible (opérande approché) → commentaire compté ; plus de 5000 entrées → les 5000 plus récentes et la phrase qui dit où sont les autres ; sauvegarde existante jamais écrasée ; les deux historiques présents : le v2 fait foi ; carnet courant existant jamais remplacé ; **copie qui échoue, écriture qui échoue, historique illisible ou plus récent : sources intactes**, conversion au démarrage suivant, jusqu'à ce qu'un carnet courant soit écrit ; seconde instance : conversion en mémoire seulement ; installation neuve : rien d'écrit |
| `DocumentServiceTest` (28) | - | enregistrement automatique du carnet sans titre **après chaque commande qui change quelque chose** (une écriture par commande, annuler et rétablir compris) et jamais pour une commande sans effet ou refusée ; disque défaillant : modification gardée, problème signalé puis réparé ; retour au démarrage suivant ; seconde instance : aucun octet du carnet courant écrit, « enregistrer sous » possible ; extension `.calc.json` ajoutée (casse, `.json`, `.calc`) ; **enregistrer sous** associe le fichier et vide le brouillon ; **document avec fichier jamais enregistré automatiquement**, indicateur « modifié » (annuler jusqu'à l'état enregistré l'efface, le mode d'affichage compte) ; échec d'enregistrement : fichier et indicateur inchangés ; ouvrir laisse le brouillon intact, garde les lignes en erreur ; fichier refusé (autre format, hostile, texte, absent, dossier) : document inchangé ; version supérieure en lecture seule, jamais réécrite, même vers le même chemin ; nouveau document, qui prévient (`wouldLoseWork`) quand un fichier ouvert a retiré de l'écran un brouillon non vide ; chemin sans nom de fichier refusé ; fichiers réservés de Calc ni ouverts ni écrasés ; carnet trop gros : erreur et aucun fichier |
| `ServicesDependenciesTest` (2) | - | aucune interface (Swing, AWT, `calc.ui`) dans `persistence`, `document`, `export` ; les couches ne pointent que vers le bas |
| `NotebookExporterTest` (27) | - | exports Markdown et CSV **comparés à des fichiers de référence** (`src/test/resources/export`, 4 modes × 2 formats) pour un carnet qui mêle exact, approché, erreur, commentaires, variables, références, ligne vide, `=`, `-`, `@` en tête, caractères Markdown et guillemets ; marque d'ordre des octets, CRLF sans saut de ligne isolé, cinq colonnes ; **injection CSV** : `=`, `+`, `-`, `@`, tabulation, retour chariot préfixés d'une apostrophe (et rien d'autre) ; guillemets RFC 4180 ; mode d'affichage sans effet sur la valeur exacte ; échappement Markdown ; commentaire hostile (`<script>`, `\|`, lien) sans effet sur le tableau ; titre échappé ; fichiers écrits de façon atomique |
| `NotebookExportProperties` (2, 300 essais chacune) | `property` | pour tout carnet et tout mode : le CSV, relu par un lecteur RFC 4180 écrit dans le test, donne une ligne par ligne non vide et cinq cellules, **aucune cellule ne peut commencer une formule**, le texte de la ligne se retrouve en retirant l'apostrophe, la colonne exacte n'est remplie que pour un résultat exact ; le tableau Markdown garde trois cellules par ligne et aucun tag ne passe |

Les fichiers de référence d'export ont été **relus à la main** avant d'être figés (valeurs, échappements, apostrophes, guillemets doublés). Quand une sortie change, le
test écrit la sortie réelle dans `build/actual-exports/` et dit où : on la relit, puis on la copie sur la référence si le changement est voulu.
`.gitattributes` empêche toute conversion de fins de ligne des `.csv` de référence (octet pour octet) et fixe LF pour les `.md`.

### Fichiers hostiles (mesures, `NotebookHostileFilesTest`)

Meilleur de trois exécutions après échauffement ; l'assertion est un budget de 2 s, pas la mesure.

| Fichier | Refus | Durée |
|---|---|---|
| un million de `[` imbriqués (1 Mo) | `JSON_LIMIT` | 0 ms |
| 500 000 objets imbriqués (2,5 Mo) | `JSON_LIMIT` | 2 ms |
| une ligne imbriquée sur 1000 niveaux | `JSON_LIMIT` | 0 ms |
| une source de 6 Mo | `JSON_LIMIT` | 6 ms |
| une source de 16 385 caractères | `JSON_LIMIT` | 0 ms |
| une clé de 6 Mo | `JSON_LIMIT` | 7 ms |
| une version de 3 millions de chiffres | `JSON_LIMIT` | 3 ms |
| un `id` qui est un nombre de 3 millions de chiffres | `JSON_LIMIT` | 3 ms |
| un exposant de 3 millions de chiffres | `JSON_LIMIT` | 3 ms |
| 5001 lignes | `TOO_MANY_LINES` | 0 ms |
| trois millions de tableaux vides (9 Mo) dans un champ inconnu | `JSON_LIMIT` (budget de valeurs) | 10 ms |
| cent mille clés inconnues | `JSON_LIMIT` (budget de valeurs) | 15 ms |
| un fichier d'un octet au-dessus de 16 Mio | `TOO_LARGE` | 0 ms |
| un fichier de 100 Mo (lu par `NotebookCodec.read`) | `TOO_LARGE`, sans lecture | 0 ms |
| un million de clés égales (6 Mo) | `MALFORMED_JSON` | 5 ms |
| 2 Mo d'octets `0xFF` | `NOT_UTF8` | 0 ms |

**Fichiers lisibles mais exigeants** : 5000 lignes (la limite) qui répètent la même ligne la plus lourde que le langage accepte. Le chargement ne calcule rien ; le recalcul
est borné par ligne (1000 caractères, 100 niveaux, 500 nœuds, nombres de 4096 bits), donc par le fichier (5000 lignes). Chargement puis recalcul complet, une exécution :

| 5000 lignes de... | Fichier | Chargement | Recalcul | Résultat |
|---|---|---|---|---|
| un littéral de 1000 chiffres | 5,2 Mo | 25 ms | 98 ms | 5000 valeurs |
| son carré (déborde 4096 bits) | 10,1 Mo | 50 ms | 208 ms | 5000 erreurs |
| `1e` suivi de 990 chiffres | 5,1 Mo | 26 ms | 170 ms | 5000 erreurs |
| `9^9^9^9^9^9^9^9^9^9` | 0,3 Mo | 4 ms | 66 ms | 5000 erreurs |
| `(99/100)^4096 * (101/100)^4096` | 0,3 Mo | 4 ms | 64 ms | 5000 erreurs |
| 99 racines imbriquées | 2,7 Mo | 15 ms | 236 ms | 5000 erreurs |
| 99 parenthèses imbriquées | 1,2 Mo | 7 ms | 41 ms | 5000 valeurs |
| 245 sommes de `1/3` (plus de 500 nœuds) | 5,1 Mo | 23 ms | 884 ms | 5000 erreurs |
| des fractions `1/997` fois un nombre de 500 chiffres | 6,3 Mo | 28 ms | 138 ms | 5000 erreurs |

Le plus lent est inférieur à une seconde sur la machine de développement (budget d'assertion de 60 s). Le recalcul reste **synchrone** : la borne est celle du langage, pas une
annulation. Un fichier conçu pour être plus lent que ces neuf cas n'a pas été trouvé ; il resterait borné par ligne.

### Preuves d'efficacité

Quatre mutations ont été faites puis retirées sans être commitées : (1) la neutralisation des formules désactivée → 14 des 29 tests d'export échouent (les quatre références CSV, l'injection, les six débuts,
les guillemets, la propriété) ; (2) le chargeur qui écarte les lignes que le langage refuse → 9 tests échouent, dont `NotebookFileFuzzProperties` ; (3) l'enregistrement automatique aussi pour un document associé à un fichier
→ `aDocumentWithAFileIsNeverSavedAutomatically...` échoue ; (4) une version supérieure non mise en lecture seule → les deux tests de version future échouent.

## Éditeur de carnet : fenêtre principale (E12)

L'éditeur suit le modèle des fenêtres précédentes : toute la logique dans `NotebookPresenter` (testable sans Swing), une
vue qui n'affiche que le `NotebookViewModel`. Choix et raccourcis : `docs/adr/0010-editeur-carnet.md`.

| Classe | Étiquette | Ce qu'elle garantit |
|---|---|---|
| `NotebookPresenterTest` | aucune | `a = 2`, `a * 3`, `ans + 1` donnent 2, 6, 7 ; modifier la ligne 1 recalcule les suivantes ; insertion au-dessus d'une ligne référencée (texte réécrit, même valeur) ; suppression (erreur explicite, annulable) ; déplacement ; annuler / rétablir (annuler avec une saisie en cours abandonne d'abord la saisie, rétablir est refusé) ; Échap ; clic ; modes (remémorisés, sauvegardés avec le carnet, saisie intacte) ; ligne trop longue refusée sans perte ; sauts de ligne collés ; fermeture qui valide ; sauvegarde automatique et rechargement ; échec de sauvegarde simulé (signalé, état en mémoire intact, réparé au coup suivant) ; deuxième instance en lecture seule (message permanent) ; migration d'un historique v1 (statut, lignes, valeurs, sauvegarde automatique) ; aucune dépendance à Swing |
| `NotebookResponsivenessTest` | aucune | 500 lignes avec références : valider la ligne 1 (recalcul, 501 lignes affichées, sauvegarde) en moins de 100 ms dans le présentateur ; coût d'un aperçu pendant la frappe (mesure pour la décision de l'ADR, section 6 ; le chiffre inclut la sauvegarde) |
| `NotebookShortcutConflictTest` | aucune | aucune touche à modificateur du carnet n'est liée par un champ texte des apparences installées ; liste les touches simples que le champ du carnet remplace |
| `NotebookPaletteContrastTest` | aucune | chaque couleur de texte des lignes atteint 4,5:1 sur ses fonds (rapports imprimés) |
| `NotebookHelpTest` | aucune | les exemples de l'aide « Syntaxe » donnent le résultat annoncé ; l'aide des raccourcis liste les vraies touches (Ctrl / Cmd) |
| `NotebookFrameTest` | `ui` | parcours clavier complet dans la vraie fenêtre (saisie, Entrée, ↑ ↓, Échap, annuler, rétablir, Ctrl+Maj+Z, déplacer, insérer, supprimer) avec le modificateur de menu du système ; Ctrl ou Cmd, jamais les deux ; badge et info-bulle sur `√2` ; message d'erreur en texte sur `1 / 0` avec la partie fautive soulignée ; variable repérée ; filigrane ; noms accessibles « Ligne n », description avec le résultat, ordre de focus ; menus et état Annuler / Rétablir ; échec d'enregistrement dans la barre d'état |
| `NotebookFrameResponsivenessTest` | `ui` | les mêmes 500 lignes dans la fenêtre : mise à jour des lignes, mise en page et peinture hors écran en moins de 100 ms ; mesure à 5000 lignes (limite du modèle) |
| `MainStartupTest` (`calc`) | `ui` | démarrage sur un répertoire de données avec un historique v1 : le carnet migré s'affiche, la première ligne validée est enregistrée, le deuxième démarrage retrouve le carnet sans migrer |

### Mesures de réactivité (`NotebookResponsivenessTest`, `NotebookFrameResponsivenessTest`)

Carnet de 500 lignes (3 variables et lignes de tête, puis `ans`, `$n` et variables, une racine toutes les 100 lignes : 500 valeurs,
dont 399 approchées, 497 lignes avec références). Machine de développement, Windows 11, JDK 21, échauffement de 30 essais, 15 mesures :

| Mesure | Meilleur | Médiane | Pire |
|---|---|---|---|
| Présentateur : Entrée sur la ligne 1 (recalcul, 501 lignes, sauvegarde du fichier) | 12,1 ms | 14,2 ms | 16,1 ms |
| Fenêtre : mise à jour des 501 lignes | 12,4 à 14,4 ms | 18,5 à 19,2 ms | 21,7 à 25,1 ms |
| Fenêtre : avec mise en page et peinture hors écran | 28,7 à 31,8 ms | 41,2 à 41,4 ms | 45,4 à 47,9 ms |
| Coût d'un aperçu pendant la frappe (recalcul + mise en forme par `DocumentService`, donc avec sa sauvegarde) | 9,6 ms | 10,8 ms | 14,6 ms |
| Ouverture de la fenêtre (500 lignes) | 0,83 à 0,85 s | | |
| 5000 lignes : mise à jour / avec mise en page et peinture | 70 à 71 ms / 89 à 91 ms | 83 à 85 ms / 112 à 115 ms | 92 à 106 ms / 120 à 140 ms |
| Ouverture de la fenêtre (5000 lignes) | 1,5 s | | |

Décision sur l'aperçu : voir l'ADR 0010, section 6 (mesuré à 11 ms, non ajouté pour une raison d'usage, pas de coût).

## Fichiers dans l'interface (E13)

Le menu Fichier, les confirmations et les messages d'ouverture sont décidés par `NotebookPresenter` ; les boîtes de dialogue sont une
interface (`NotebookDialogs`) que les tests remplacent par `FakeDialogs`. Aucun test n'ouvre une vraie boîte de dialogue. Choix :
`docs/adr/0011-fichiers-dans-l-interface.md`, qui porte aussi le tableau de parité avec l'ancien formulaire et la liste des tests retirés.

| Classe | Étiquette | Ce qu'elle garantit |
|---|---|---|
| `NotebookFilesTest` | aucune | Nouveau, Ouvrir, Enregistrer, Enregistrer sous (extension ajoutée, confirmation avant de remplacer un fichier, nom réservé refusé) ; les trois choix de la confirmation pour Nouveau, Ouvrir et Quitter ; carnet sans titre jamais interrogé pour Ouvrir et Quitter ; version plus récente en lecture seule (Enregistrer refusé, Enregistrer sous permis) ; fichier invalide, trop volumineux, version illisible, réservé ou absent : raison dite, document et modifications conservés ; lignes en erreur comptées ; exports Markdown et CSV comparés octet pour octet aux fichiers de référence d'E11 dans les quatre modes ; confirmation d'écrasement ; échec d'écriture signalé ; aucune dépendance à Swing |
| `NotebookFrameFilesTest` | `ui` | le menu Fichier est le premier et complet ; ses touches suivent le modificateur de chaque système (Ctrl ou Cmd, jamais les deux) et existent dans le champ de la ligne ; parcours Enregistrer sous, modification (titre marqué `•`), Enregistrer (marqueur retiré) ; fermeture de la fenêtre et Quitter passent par la confirmation (Annuler garde la fenêtre) ; Enregistrer désactivé pour une version plus récente |
| `NotebookParityTest` | aucune | une fonction de l'ancien formulaire par test (P1 à P16) : calcul binaire et unaire, virgule ou point, enchaînement sans dérive, `ans`, `$n`, un tiers × 3, valeur approchée réutilisée, Échap, Tout effacer, persistance, erreurs ciblées, bornes de taille, modes d'affichage, détail d'exactitude |
| `NotebookLosslessChainProperties` | `property` | une chaîne aléatoire d'opérations exactes sur `ans`, tapée dans le présentateur, finit exactement sur la valeur d'un oracle indépendant (150 essais) |
| `NotebookPresenterStatefulProperties` | `robustness` | des séquences aléatoires de saisies, déplacements, annulations, modes et opérations de fichier (Nouveau, Enregistrer sous, Enregistrer, Ouvrir, Exporter, Quitter) : la vue est celle d'une évaluation neuve des textes, le carnet sans titre est le fichier automatique, un document différent de son fichier est marqué ; vérifié en retirant le marqueur du titre (le test échoue sur `[SAVE_AS, LINE(1 + 1)]`) |
| `calc.engine.DisplayRulesTest`, `SizeLimitsTest`, `GoldenOutputsTest` | aucune | les anciens tests du moteur qui passaient par l'ancien présentateur, déplacés sans perdre une assertion du moteur |
| `calc.persistence.JsonHistoryBackupTest`, `PersistenceLoggingTest` | aucune | copies `.bak` et journaux de l'ancien historique (désormais seulement la source de la migration), plus le journal d'un échec de sauvegarde automatique du carnet |

Mesures (`gradlew clean check`, Windows 11, JDK 21) : 1057 tests, 0 ignoré, 0 échec, contre 1090 avant E13 (3505 lignes retirées avec l'ancien formulaire et ses tests, les tests du menu Fichier et de la parité ajoutés).

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
5. **Le magasin rejetait ses propres lignes** (revue indépendante de E06, corrigé). Une entrée calculée à partir d'une
   racine mémorisée (`√2` puis `× 123456789012345`, `1e15 ÷ √2`) était écrite avec un point de l'intervalle du résultat,
   et le rechargement, qui recalcule exactement depuis le point de l'opérande, trouvait un autre point qui différait dès
   la 12e décimale : ligne « incohérente », écartée. Les générateurs ne produisaient que des opérandes saisis. Correction :
   la colonne résultat de ces lignes est le recalcul exact depuis les opérandes enregistrés (ADR 0004, section 9).
   Régression : `HistoryRoundTripProperties.largeResultsCalculatedFromARememberedRootReload` et la propriété
   `entriesCalculatedFromRememberedRootsReloadWithoutRejection` ; sans la correction, les deux échouent.

6. **La position d'une opération autour d'une parenthèse ne désignait pas son texte** (E09, corrigé avant le premier
   commit des propriétés). Trouvé en écrivant `ExpressionRoundTripProperties.theSpanOfEveryNodeReadsBackAsThatNode` :
   dans `(a + b) * c`, la multiplication allait de `a` à `c` (`a + b) * c`), parce qu'une expression entre parenthèses
   gardait la position de son contenu. Correction : la position d'un opérande entre parenthèses inclut les parenthèses.
   Régression : la propriété elle-même et les positions de `ExpressionErrorsTest` (`1 / (2 - 2)` → `(2 - 2)`).
7. **Une borne de pile annoncée sans preuve** (E09, corrigé avant commit). Les plafonds « sûrs » d'abord choisis
   (1000 niveaux d'analyse, 5000 nœuds évalués récursivement) faisaient déborder la pile dès le premier test placé à la
   borne. Correction : l'évaluateur et l'imprimeur parcourent l'arbre avec une pile explicite, et la profondeur d'analyse
   est plafonnée à 100 ; `ExpressionRobustnessTest` le vérifie sur une pile de 512 Ko (256 Ko suffisaient sous Windows
   mais débordaient sous macOS arm64, découvert par la CI).

## Limites connues

- Le budget de 100 ms de `InputFuzzProperties` est une mesure de temps : il prend le meilleur de deux essais et suit un échauffement, mais une machine très chargée peut encore l'invalider.
- Le recalcul d'un carnet hostile est borné par ligne, pas annulable : les neuf formes de lignes lourdes mesurées (E11) restent sous la seconde pour 5000 lignes, mais aucune preuve ne dit qu'aucune forme n'est plus lente.
- Le modèle de carnet accepte une ligne plus longue que ce que le fichier écrit (16 384 caractères) : l'éditeur (E12) la refuse avec un message (`NotebookPresenterTest`), mais le modèle lui-même ne la borne toujours pas.
- Les mesures de réactivité de l'éditeur (E12) sont des mesures de temps sur la machine de développement : le budget de 100 ms est contrôlé sur la médiane de 15 essais après échauffement, et la médiane mesurée (14 à 41 ms) laisse une marge d'un facteur 2 à 7 ; une machine de CI très chargée pourrait quand même l'invalider.
- L'éditeur n'est pas contrôlé sur un clavier macOS réel ni avec l'apparence Aqua : les touches sont vérifiées par leur liaison dans le champ (avec `Cmd` simulé) et par les apparences installées dans le JDK de la machine.
- Les générateurs choisissent leurs formes de valeurs : les cas non prévus (autres alphabets d'espaces, très gros nombres de lignes d'historique) ne sont pas couverts. `-Pjqwik.tries.default=20000` élargit la recherche sans rien changer au code.
- Le modèle de session du présentateur reproduit la règle de la mémoire des champs : une erreur commune au modèle et au présentateur ne serait pas vue ; `CalculatorPresenterTest` la fixe aussi par des exemples.
- L'oracle n'a qu'une précision de 200 chiffres : les propriétés qui l'utilisent gardent des opérandes assez courts pour que le résultat exact y tienne, et vérifient que c'est bien le cas.
