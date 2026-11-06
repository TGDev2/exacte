# Recette finale indépendante de Calc (E16)

Date : 2026-11-06. Commit recetté : `main` à `0bdd534`, plus la branche de recette. Ce rapport distingue le défaut avéré (reproduit), le risque (plausible, non reproduit) et la préférence (discutable).

## 1. Méthode et environnement

- Système : Windows 11 Pro 10.0.26200, amd64, affichage disponible. JDK des tests et du paquet : Temurin 21.0.7 (chaîne d'outils Gradle), Gradle 9.8.2.
- Build : clone neuf dans un répertoire temporaire, `gradlew clean check`, puis mutation, JAR et paquet dans ce clone.
- Données : uniquement des répertoires temporaires (`APPDATA` redirigé pour `Calc.exe`, répertoire injecté dans `NotebookStore.open` pour les tests). Les préférences ont été éprouvées sur un nœud dédié, supprimé ensuite. `%APPDATA%\Calc\history.tsv` a le même SHA-256 avant et après (`AEE53300…C80E0`).
- Revue de code et contrôle de la documentation : confiés à trois relectures séparées, qui ont reproduit leurs constats avec des programmes jetables. Le recetteur a rejoué lui-même D1 et le défaut bloquant ; les autres reproductions sont rapportées telles quelles.
- Limite : les relectures ont été arrêtées plus tôt que prévu pour tenir le budget de la tâche. Leur revue est donc ciblée, pas exhaustive. L'interface a été relue par recherche dans le code seulement.
- Non vérifiable ici : macOS, Linux, Excel, rendu visuel. Ces points vont à la section 8.

## 2. Résultats du build et de la livraison

| Commande | Résultat |
|---|---|
| `gradlew clean check` (clone de `main`) | BUILD SUCCESSFUL en 1 min 7 ; 1114 tests, 85 classes, 0 échec, 0 ignoré |
| `gradlew conformity -PrequireUiTests=true` (inclut `check`, PIT et `conformityReport`) | code 0 ; verdict CONFORME |
| `gradlew clean jar`, deux fois | même SHA-256 : `64B433DB3DDCEF6B1BADCD877F8E4CD312D7674420B13DE9B41ABF5C504DC9AF` |
| `gradlew packageApp '-PappVersion=1.0.0'` | code 0 ; `Calc-1.0.0-windows-x64.zip`, 35 955 866 octets, 147 fichiers |

Tests par tag, sur la branche de recette à `55a2a4f` (`-PincludeTags=...`, avec `-PrequireUiTests=true`) :

| Tag | Tests | Échecs | Ignorés |
|---|---|---|---|
| acceptance | 92 | 31 (les tests de ce rapport, qui n'existait pas encore) | 0 |
| property | 91 | 0 | 0 |
| robustness | 57 (24 sur `main`) | 0 | 0 |
| ui | 27 | 0 | 0 |

**Rapport de conformité.** Ses chiffres concordent avec les XML bruts : 1114 tests, 0 échec, 0 ignoré ; property 91, robustness 24, ui 27. Mutation : 1575 mutants viables, 1442 détectés, score 91,6 %, tous les seuils tenus (`calc.expression` 98,7 %, `calc.format` 96,1 %, `calc.notebook` 91,0 %, `calc.persistence` 88,0 %, `calc.number` 87,3 %). Deux réserves : voir D9 et le risque R5.

**Application packagée.** `Calc.exe`, données redirigées : processus vivant après 7 s et `history.lock` créé. Le titre de fenêtre lu sur le processus est vide, la fenêtre n'a donc pas été observée de cette façon. Le JRE embarqué contient `java.base java.datatransfer java.xml java.prefs java.desktop java.logging jdk.localedata`. Une sonde exécutée par ce même JRE (`Calc.cfg` modifié dans une copie) a prouvé : préférence relue `FRACTION` ; document `.calc.json` enregistré ; CSV avec BOM (premier octet 239) et Markdown exportés ; `ans * 3` donne `1` ; fenêtre réelle visible, titre `• devis.calc.json — Calc`. Les vrais `JFileChooser` n'ont pas été pilotés. Le paquet vient de `0bdd534`, avant la correction 6ab5610.

**CI et release, consultées avec `gh `.**

- Dernière CI sur `main` (run 42194527982, `0bdd534`) : **rouge, les 5 jobs n'ont pas démarré**. Message de GitHub : paiement du compte en échec ou limite de dépense atteinte. Ce n'est pas un échec de test, mais ce commit n'a aucune validation distante.
- Dernière CI verte sur `main` (run 40499634648, `137d4cb`) : Windows, macOS, Ubuntu et JAR reproductible. Le workflow impose `-PrequireUiTests=true` ; les journaux de ce run n'ont pas été lus.
- Releases : **aucune**. `gh release list` est vide et `gh run list --workflow=Release` aussi : le workflow n'a jamais tourné. SHA256SUMS, SBOM et archives publiées n'ont pas pu être contrôlés.

**Contrôle final de la branche, rapport inclus.** `.\gradlew.bat test --rerun --tests *AcceptanceTest` : BUILD SUCCESSFUL, 92 tests, 0 échec, 0 ignoré. `.\gradlew.bat clean check -PrequireUiTests=true` : BUILD SUCCESSFUL en 1 min 8, 1209 tests (1114 + 92 de recette + 3 de ReservedFileAliasTest), 91 classes, 0 échec, 0 ignoré.

## 3. Résultats par garantie

| Garantie | Preuve | Statut |
|---|---|---|
| Idée 1 : `1 / 3` puis `ans * 3` donne `1` exact | ExactEngineAcceptanceTest ; sonde du JRE embarqué | OK |
| Idée 1 : `√2` approché, borne d'erreur, vraie valeur dans l'intervalle | ExactEngineAcceptanceTest | OK |
| Idée 1 : `1 / 3000000000000` et `3 ^ -30` non nuls dans les 4 modes | ExactEngineAcceptanceTest | OK |
| Idée 1 : `√(9/4)` donne `3/2` exact | ExactEngineAcceptanceTest ; relecture du noyau | OK |
| Idée 1 : 50 opérations exactes enchaînées restent exactes | ExactEngineAcceptanceTest | OK |
| Idée 1 : entrées énormes refusées rapidement | ExactEngineAcceptanceTest (0 à 3 ms) | OK |
| Idée 1 : réutilisation sans perte | NotebookLosslessChainProperties | OK |
| Idée 1 : une valeur non nulle n'est jamais affichée « 0 » | ExactEngineAcceptanceTest pour les cas prévus ; contre-exemple D1 | Réserve |
| Idée 2 : scénario devis, cascade | NotebookScenariosAcceptanceTest | OK |
| Idée 2 : scénario budget, `$n`, `$?`, déplacement, annuler et rétablir tout | NotebookScenariosAcceptanceTest ; NotebookPresenterStatefulProperties | OK |
| Idée 2 : enregistrer sous, rouvrir, mêmes lignes et résultats | NotebookScenariosAcceptanceTest | OK |
| Idée 2 : exports Markdown et CSV relus | NotebookScenariosAcceptanceTest | OK |
| Idée 2 : injection de formules neutralisée | NotebookScenariosAcceptanceTest pour les cas prévus ; risque R1 | Réserve |
| Migration : chaque fichier de `legacy/v1/` vers le carnet v3, sauvegardes, relance sans effet | MigrationAcceptanceTest | OK, voir D3 |
| Robustesse : 9 fichiers hostiles au menu et au démarrage, moins de 1 s | RobustnessAcceptanceTest (24 ms au pire) | OK |
| Robustesse : aucun dépassement de mémoire avec `-Xmx128m` | RobustnessAcceptanceTest | OK, voir R2 |
| Robustesse : version future jamais réécrite | RobustnessAcceptanceTest | OK |
| Robustesse : la seconde instance n'écrit rien | RobustnessAcceptanceTest ; StoreLockTest | OK |
| Robustesse : échec d'écriture simulé, aucun fichier corrompu | RobustnessAcceptanceTest | OK |
| Robustesse : un document ne remplace pas le carnet courant | ReservedFileAliasTest | Corrigé |
| Idée 3 : Gradle, JUnit 5, propriétés, tests d'interface | `gradlew clean check`, section 2 | OK |
| Idée 3 : mutation avec seuils et rapport généré | `gradlew conformity`, section 2 | OK, voir R5 |
| Idée 3 : JAR reproductible | deux builds, même SHA-256 | OK |
| Idée 3 : CI sur 3 OS | run 40499634648 vert sur `137d4cb` ; run de `0bdd534` non démarré | Réserve |
| Idée 3 : releases avec SHA-256 et SBOM | workflow lu mais jamais exécuté, aucune release | Non prouvé |

## 4. Défauts

### Défauts bloquants corrigés

| Défaut | Test | Commit |
|---|---|---|
| Perte de données : `NotebookStore.isReserved` ne comparait que les chemins normalisés. Ouvrir `carnet-courant.calc.json.` (point final ignoré par Windows) ou passer par une jonction, puis enregistrer, écrasait le carnet courant. | ReservedFileAliasTest (2 tests sur 3 échouaient avant la correction) | 6ab5610 |

### Autres défauts consignés

D1 relève d'une décision de conception et reste ouvert. Le classer comme non bloquant est un jugement du recetteur, que le propriétaire peut contester : l'affichage porte le marqueur `≈` et une borne exacte, mais la phrase « jamais 0 » n'est pas tenue à la lettre.

| N° | Gravité | Défaut | Reproduction |
|---|---|---|---|
| D1 | Majeure | Une valeur non nulle s'affiche `≈ 0` quand l'arrondi extérieur ramène la borne basse à 0 (`OutwardRounding.java:50`), alors que l'exact `(1/16)^4096` est refusé | `(sqrt(2) / 16) ^ 4096` donne `≈ 0`, APPROXIMATE, `Erreur maximale : ± 9,6 × 10⁻¹²³⁴`. Aussi `√2 - √2 + 1e-40` |
| D2 | Moyenne | Le fichier temporaire au nom fixe `<nom>.tmp` détruit un fichier de l'utilisateur portant ce nom (`AtomicFileWriter.java:71`) | Créer `doc.calc.json.tmp`, puis enregistrer sous `doc.calc.json` |
| D3 | Moyenne | Des entrées d'historique rejetées à la migration ne sont pas signalées (`NotebookStore.java:235`). Les sources restent sauvegardées ; cause du rejet non recherchée | Un TSV de 7 lignes donne « 3 lignes », `commented=0` |
| D4 | Moyenne | Une définition en erreur laisse les lignes suivantes utiliser l'ancienne valeur (`NotebookEvaluator.java:33`) | `x = 1`, `x = 2 +`, `x` : la dernière ligne vaut l'ancien `x` |
| D5 | Faible | Le service d'enregistrement sous remplace un autre fichier de version future (`DocumentService.java:232`) ; le présentateur demande d'abord confirmation | Appel direct de `saveAs` vers un fichier `version: 99` |
| D6 | Faible | Le chiffre affiché peut sortir de l'intervalle (`ValueFormatter.java:221`) | `1,5+(sqrt(2)-sqrt(2))*2e23` donne `≈ 2…`, ± 8,3e-2 |
| D7 | Faible | Pour un intervalle contenant 0, Fraction et Auto diffèrent (`ValueFormatter.java:147`) | `between(-1,11)` : Fraction `≈ 5`, Auto `≈ 0` |
| D8 | Cosmétique | Décimal sous le seuil annoncé de 10⁻¹⁵ (`ValueFormatter.java:132`) | `between(1e-300,1e-15)` donne `≈ 0,0000000000000005…` |
| D9 | Faible | `hs_err_*.log` et `replay_*.log` ne sont pas ignorés par git : le rapport de conformité d'un clone propre indique « + modifications non commitées » | `gradlew conformity` sous manque de mémoire, voir `git status` |

## 5. Risques et préférences

**Risques (plausibles, non reproduits dans un tableur ou en conditions réelles)**

- R1. CSV : un espace, une espace insécable ou U+0001 avant `=`, et les caractères pleine largeur `＝＠＋－`, ne sont pas neutralisés (`NotebookExporter.java:150`). Le cas ` =1` est même fixé comme non neutralisé par NotebookExporterTest. Les virgules ne sont pas citées, ce qui peut isoler une formule pour un lecteur à séparateur virgule. Seuls les octets ont été vus : à essayer dans Excel (section 8). Un correctif a été écrit puis retiré, car il contredisait ce choix testé sans exploitation prouvée.
- R2. `HistoryCodec.java:184` ne borne pas le nombre de valeurs : un `history.json` de 3 millions de valeurs provoque `OutOfMemoryError` à `-Xmx128m`, mais se charge avec le tas par défaut.
- R3. Pas de `fsync` du répertoire ni des sauvegardes (lecture du code seulement).
- R4. Deux instances sur le même document : le dernier qui enregistre gagne, sans avertissement.
- R5. Pendant la mutation, 4 JVM de PIT sont mortes faute de mémoire native (machine chargée, `hs_err_pid*`). Elles ne viennent pas de l'application, mais un mutant ainsi interrompu a pu fausser le score : à remesurer sur une machine calme.
- R6. Aucun `SwingWorker` dans `src/main` : tout le calcul tourne sur le thread Swing. Le travail est borné par ligne et NotebookFrameResponsivenessTest passe, mais rien ne prouve l'absence d'une forme plus lente.
- R7. La forme canonique de `2^4095+1` (1233 caractères) dépasse la limite d'une ligne ; l'effet sur le pont d'historique n'a pas été suivi.

**Préférences**

- Identifiants de ligne réutilisés après un rechargement (sans effet observé).
- Supprimer une définition résout de nouveau variables et `ans` par position (ADR 0008).
- `≈ 0 ± 5` pour l'intervalle [0, 10] : le rayon est mesuré autour du centre.

## 6. Écarts entre documentation et réalité

Environ 90 affirmations du README et 105 du guide utilisateur ont été contrôlées (ordre de grandeur, non décomptées une à une). Écarts :

- `README.md:104` : fichier abîmé « jamais écrasé ». En réalité, il est remplacé à la première ligne validée, le `.bak` étant conservé. Trompeur.
- `README.md:102`, `docs/GUIDE-UTILISATEUR.md:33` : « jamais 0 ». Voir D1.
- `README.md:105`, guide:114 : migration « sans perte », une ligne par calcul. Au-delà de 5000 entrées, les plus anciennes restent seulement dans la sauvegarde ; certaines deviennent des commentaires. Voir aussi D3.
- `README.md:24-25`, guide:80 et 91 : seul `Ctrl+M` est signalé « toujours Ctrl », or `Ctrl+Maj+↑/↓` l'est aussi (`NotebookShortcuts.java:68`). Trompeur sur macOS.
- Guide:72-73 : le résultat est le texte affiché, mais les exports ajoutent la borne (`≈ 1,41421356237… (± 2,1 × 10⁻²⁵)`).
- Guide:118 : le fichier abîmé est « remplacé par un carnet vide », en réalité seulement à la ligne validée suivante.
- Guide:131 : la nouvelle tentative à l'action suivante ne vaut que pour le carnet sans titre (`DocumentService.java:274-276`).
- Guide:62-63 : le dialogue apparaît aussi pour Nouveau sur un carnet sans titre non vide (`NotebookPresenter.java:384`).
- Guide:53-58 : le menu Édition et « Tout effacer » ne sont pas décrits.
- `docs/GUIDE-EXPRESSIONS.md:3-5` : parle d'un « futur carnet », alors qu'il est la fenêtre principale.
- `docs/GUIDE-EXPRESSIONS.md:128` : le message de `$9` cité n'est pas celui affiché (`NotebookEvaluator.java:175`).
- `docs/GUIDE-EXPRESSIONS.md:27,60` : exposants ±9999 et ±4096 ; ce sont des bornes de syntaxe, `1e9999` et `2^4096` étant refusés comme trop grands.
- `docs/FORMAT-FICHIER.md:14,98` : décrit `history.json` et « l'historique de cette fenêtre » ; c'est désormais `carnet-courant.calc.json` et « le carnet de cette fenêtre ».
- `docs/TESTS.md:457` : 1114 tests en 85 classes, chiffre d'avant la recette.
- `CLAUDE.md` du dépôt : décrit encore l'état d'avant Gradle (Maven à venir, `Calcul/`, 23 tests, pas de notation scientifique).
- Non vérifiés : les temps de `README.md:116`, l'ouverture dans Excel, le comportement sur macOS, CycloneDX 1.6 (aucun SBOM généré ici).

## 7. Verdict par idée

### Idée 1 : moteur exact — livrée avec réserves

Exactitude, borne certifiée, modes d'affichage et réutilisation sans perte tiennent dans tous les scénarios prévus, et la relecture a trouvé les sens d'arrondi, la racine et les limites vérifiées avant calcul corrects (15 000 essais aléatoires sans échec). La réserve est D1 : hors de la plage usuelle, une valeur non nulle peut s'afficher `≈ 0`. Le marqueur et la borne restent vrais, mais la promesse écrite est plus forte que le comportement. Il faut trancher entre refuser ce cas et corriger la promesse.

### Idée 2 : carnet de calcul — livrée avec réserves

Expressions, références stables, annuler et rétablir, documents, sauvegarde automatique, exports et migration de tous les fichiers de référence passent de bout en bout, et 120 000 pas contre un oracle n'ont trouvé aucune redirection de référence. Un défaut bloquant de perte de données a été trouvé et corrigé. Restent D2, D3 et D4, qui ne cassent pas les scénarios nominaux, et le risque R1 sur la neutralisation CSV, qu'il faut éprouver dans Excel avant de la présenter comme une garantie de sécurité complète.

### Idée 3 : fiabilité prouvée — livrée avec réserves

Build Gradle, JUnit 5, tests par propriétés et de robustesse, tests d'interface, seuils de mutation, rapport généré, JAR reproductible et paquet Windows ont tous été exécutés ici avec succès. En revanche, la CI du dernier commit de `main` n'a pas démarré (facturation du compte), macOS et Linux n'ont été vus qu'à la CI verte de `137d4cb`, et la chaîne de release n'a **jamais tourné** : aucune archive, aucun SHA256SUMS ni SBOM publié n'existe. Cette dernière partie de la promesse est écrite mais non démontrée.

## 8. Check-list manuelle

- [ ] Rétablir la facturation GitHub Actions, puis vérifier une CI verte sur `main` avec les tests d'interface exécutés sur les 3 OS.
- [ ] Lancer le workflow Release une première fois (pré-version), en décider le numéro.
- [ ] Parcours complet au clavier sur Windows (Tab, flèches, Entrée, Ctrl+Z/Y, menus, dialogues de fichiers réels).
- [ ] Essai de l'application sur macOS et Linux (raccourcis, dialogues natifs).
- [ ] Ouverture d'un CSV exporté dans Excel : séparateur, accents, et cellules ` =1+1`, `＝1+1`, `1,=1+1` (risque R1).
- [ ] Lisibilité des badges exact et approché, en clair et selon le thème du système.
- [ ] Téléchargement d'une release et vérification des SHA-256 avec SHA256SUMS, lecture du SBOM.
- [ ] Avertissements SmartScreen (Windows) et Gatekeeper (macOS) au premier lancement.
- [ ] Trancher D1 : refuser la valeur, ou reformuler la promesse « jamais 0 ».

## 9. Recommandations hiérarchisées

Classées par criticité, puis impact, puis coût.

| Rang | Recommandation | Criticité | Impact | Coût |
|---|---|---|---|---|
| 1 | Rétablir la CI et exécuter une première release | Haute | Prouve la partie non démontrée de l'idée 3 | Faible |
| 2 | Décider et traiter D1 | Haute | Rend la promesse « jamais 0 » exacte | Faible à moyen |
| 3 | Éprouver R1 dans Excel, puis élargir la neutralisation si besoin | Haute | Sécurité des exports | Faible |
| 4 | Corriger D3 : signaler les entrées rejetées, et en chercher la cause | Moyenne | Confiance dans la migration | Faible |
| 5 | Corriger D2 : nom temporaire unique | Moyenne | Évite une perte rare | Faible |
| 6 | Traiter D4 : une définition en erreur invalide les lignes qui en dépendent | Moyenne | Évite un résultat trompeur | Moyen |
| 7 | Mettre à jour README, guides, `CLAUDE.md` (section 6) ; ignorer `hs_err_*` (D9) | Moyenne | Documentation fiable | Faible |
| 8 | Borner `HistoryCodec` (R2), remesurer la mutation (R5) | Faible | Marge de sûreté | Faible |
