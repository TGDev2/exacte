# Recette finale indépendante de Calc (E16)

Date : 2026-11-06. Commit recetté : `main` à `0bdd534`, plus la branche de recette. Ce rapport distingue le défaut avéré (reproduit), le risque (plausible, non reproduit) et la préférence (discutable).

## 1. Méthode et environnement

- Système : Windows 11 Pro 10.0.26200, amd64, affichage disponible. JDK des tests et du paquet : Temurin 21.0.7 (chaîne d'outils Gradle), Gradle 9.8.2.
- Build : clone neuf dans un répertoire temporaire, `gradlew clean check`, puis mutation, JAR et paquet dans ce clone.
- Données : uniquement des répertoires temporaires (`APPDATA` redirigé pour `Calc.exe`, répertoire injecté dans `NotebookStore.open` pour les tests). Les préférences ont été éprouvées sur un nœud dédié, supprimé ensuite. `%APPDATA%\Calc\history.tsv` a le même SHA-256 avant et après (`AEE53300…C80E0`).
- Revue de code et contrôle de la documentation : confiés à des relectures séparées, en deux passes, qui ont reproduit leurs constats avec des programmes jetables. La seconde passe a rejoué les reproductions de la première : elle en a confirmé la plupart et en a réfuté une (l'ancien D4). Le recetteur a lui-même rejoué D1 et le gel de la fenêtre, et chaque correction a son test rouge d'abord.
- Non atteint par la revue : vraie coupure de processus et vrai disque plein (états simulés), échec à l'étape d'écriture de la migration, lecture complète de `HistoryCodec`, `JsonParser`, `StoreLock`, `Notebook` et `NotebookEditor`, vrais dialogues et saisie au robot, verrou après un blocage, carnet lourd complet dans une vraie fenêtre (arrêté à 560 s).
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

**Rapport de conformité.** Ses chiffres concordent avec les XML bruts : 1114 tests, 0 échec, 0 ignoré ; property 91, robustness 24, ui 27. Mutation, mesurée deux fois : sur la machine chargée, 1442 mutants détectés sur 1575, score 91,6 %, avec 4 JVM de PIT mortes faute de mémoire ; sur la machine calme, 1445 sur 1575, score 91,7 %, aucun plantage. La seconde mesure fait foi, tous les seuils tenus (`calc.expression` 98,7 %, `calc.format` 96,9 %, `calc.notebook` 91,5 %, `calc.persistence` 88,0 %, `calc.number` 87,6 %). Réserve : voir D9.

**Application packagée.** `Calc.exe`, données redirigées : processus vivant après 7 s et `history.lock` créé. Le titre de fenêtre lu sur le processus est vide, la fenêtre n'a donc pas été observée de cette façon. Le JRE embarqué contient `java.base java.datatransfer java.xml java.prefs java.desktop java.logging jdk.localedata`. Une sonde exécutée par ce même JRE (`Calc.cfg` modifié dans une copie) a prouvé : préférence relue `FRACTION` ; document `.calc.json` enregistré ; CSV avec BOM (premier octet 239) et Markdown exportés ; `ans * 3` donne `1` ; fenêtre réelle visible, titre `• devis.calc.json — Calc`. Les vrais `JFileChooser` n'ont pas été pilotés. Le paquet vient de `0bdd534`, avant les corrections de la section 4.

**CI et release, consultées avec `gh `.**

- Dernière CI sur `main` (run 42194527982, `0bdd534`) : **rouge, les 5 jobs n'ont pas démarré**. Message de GitHub : paiement du compte en échec ou limite de dépense atteinte. Ce n'est pas un échec de test, mais ce commit n'a aucune validation distante.
- Dernière CI verte sur `main` (run 40499634648, `137d4cb`) : Windows, macOS, Ubuntu et JAR reproductible. Le workflow impose `-PrequireUiTests=true` ; les journaux de ce run n'ont pas été lus.
- Releases : **aucune**. `gh release list` est vide et `gh run list --workflow=Release` aussi : le workflow n'a jamais tourné. SHA256SUMS, SBOM et archives publiées n'ont pas pu être contrôlés.

**Contrôle final de la branche, rapport inclus.** `.\gradlew.bat test --rerun --tests *AcceptanceTest` : BUILD SUCCESSFUL, 92 tests, 0 échec, 0 ignoré. `.\gradlew.bat clean check -PrequireUiTests=true` : BUILD SUCCESSFUL, 1219 tests, 95 classes, 0 échec, 0 ignoré.

## 3. Résultats par garantie

| Garantie | Preuve | Statut |
|---|---|---|
| Idée 1 : `1 / 3` puis `ans * 3` donne `1` exact | ExactEngineAcceptanceTest ; sonde du JRE embarqué | OK |
| Idée 1 : `√2` approché, borne d'erreur, vraie valeur dans l'intervalle | ExactEngineAcceptanceTest | OK |
| Idée 1 : un texte `\|x\| ≤ b` ou un centre grossier et sa borne contiennent la vraie valeur (ailleurs, les chiffres sont l'arrondi correct de tout l'intervalle et la borne reste le rayon autour du centre, ADR 0004) | ShownValueBoundTest ; NeverShownAsZeroProperties | Corrigé |
| Idée 1 : `1 / 3000000000000` et `3 ^ -30` non nuls dans les 4 modes | ExactEngineAcceptanceTest | OK |
| Idée 1 : `√(9/4)` donne `3/2` exact | ExactEngineAcceptanceTest ; relecture du noyau | OK |
| Idée 1 : 50 opérations exactes enchaînées restent exactes | ExactEngineAcceptanceTest | OK |
| Idée 1 : entrées énormes refusées rapidement | ExactEngineAcceptanceTest (0 à 3 ms) | OK |
| Idée 1 : réutilisation sans perte | NotebookLosslessChainProperties | OK |
| Idée 1 : une valeur non nulle n'est jamais affichée « 0 » | ExactEngineAcceptanceTest pour les cas prévus ; NeverShownAsZeroAcceptanceTest et NeverShownAsZeroProperties pour le contre-exemple D1 | Corrigé |
| Idée 2 : scénario devis, cascade | NotebookScenariosAcceptanceTest | OK |
| Idée 2 : scénario budget, `$n`, `$?`, déplacement, annuler et rétablir tout | NotebookScenariosAcceptanceTest ; NotebookPresenterStatefulProperties | OK |
| Idée 2 : enregistrer sous, rouvrir, mêmes lignes et résultats | NotebookScenariosAcceptanceTest | OK |
| Idée 2 : exports Markdown et CSV relus | NotebookScenariosAcceptanceTest | OK |
| Idée 2 : injection de formules neutralisée | NotebookScenariosAcceptanceTest pour les cas prévus ; risque R1 | Réserve |
| Migration : chaque fichier de `legacy/v1/` vers le carnet v3, sauvegardes, relance sans effet ; lignes légitimes reprises, lignes rejetées annoncées | MigrationAcceptanceTest ; LegacyDoubleResultTest ; MigrationRejectionNoticeTest | Corrigé |
| Robustesse : 9 fichiers hostiles au menu et au démarrage, moins de 1 s | RobustnessAcceptanceTest (24 ms au pire) | OK |
| Robustesse : aucun dépassement de mémoire avec `-Xmx128m` | RobustnessAcceptanceTest | OK, voir R2 |
| Robustesse : version future jamais réécrite | RobustnessAcceptanceTest pour le carnet courant et l'enregistrement ; FutureVersionTargetAcceptanceTest pour la cible d'un Enregistrer sous (D5) | Corrigé |
| Robustesse : la seconde instance n'écrit rien | RobustnessAcceptanceTest ; StoreLockTest | OK |
| Robustesse : échec d'écriture simulé, aucun fichier corrompu | RobustnessAcceptanceTest | OK |
| Robustesse : un document ne remplace pas le carnet courant | ReservedFileAliasTest | Corrigé |
| Robustesse : un fichier temporaire n'écrase pas un fichier existant | TemporaryNameCollisionTest | Corrigé |
| Robustesse : un carnet hostile ne bloque pas la fenêtre | Programme jetable : 37 ms par ligne de 974 caractères, 182 s pour 5000 lignes | Non tenu |
| Idée 3 : Gradle, JUnit 5, propriétés, tests d'interface | `gradlew clean check`, section 2 | OK |
| Idée 3 : mutation avec seuils et rapport généré | `gradlew conformity`, section 2 | OK |
| Idée 3 : JAR reproductible | deux builds, même SHA-256 | OK |
| Idée 3 : CI sur 3 OS | run 40499634648 vert sur `137d4cb` ; run de `0bdd534` non démarré | Réserve |
| Idée 3 : releases avec SHA-256 et SBOM | workflow lu mais jamais exécuté, aucune release | Non prouvé |

## 4. Défauts

### Défauts bloquants corrigés

Chaque test échouait avant sa correction. Le contrôle final les exécute tous.

| Défaut | Test | Commit |
|---|---|---|
| Perte de données : `NotebookStore.isReserved` ne comparait que les chemins normalisés. Ouvrir `carnet-courant.calc.json.` (point final ignoré par Windows) ou passer par une jonction, puis enregistrer, écrasait le carnet courant. | ReservedFileAliasTest | 6ab5610 |
| Garantie violée : `1,5+(sqrt(2)-sqrt(2))*2e23` affichait `≈ 2… ± 8,3 × 10⁻²`, qui exclut la vraie valeur 1,5 ; de même `≈ 0 ± 6` pour [-1, 11]. La borne vaut maintenant depuis le texte affiché (`≈ 2… ± 5,9 × 10⁻¹` à ce commit, `≈ 1,5… ± 8,3 × 10⁻²` depuis le correctif de D6). ADR 0004 complété. | ShownValueBoundTest | e205a4c |
| Perte de données : à la migration, les résultats écrits en `double` par le premier format v1 étaient rejetés sans message (`DIVISION 1000000 3 333333.3333333333`). Ces lignes sont acceptées si le texte est exactement celui de cet ancien calcul. | LegacyDoubleResultTest | bce6665 |
| Perte silencieuse : les lignes d'historique encore rejetées à la migration (`POWER 2 0,5`, résultat incohérent) disparaissaient du carnet sans message. Le statut annonce maintenant leur nombre et le fichier qui les conserve (`history.tsv.v1.bak`). Elles ne sont toujours pas dans le carnet. | MigrationRejectionNoticeTest | 130973e |
| Perte de données : le temporaire au nom fixe `<nom>.tmp` détruisait un fichier de l'utilisateur portant ce nom, sans confirmation. | TemporaryNameCollisionTest | 714a506 |
| Gel de la fenêtre : un carnet valide de 5000 lignes coûteuses bloquait le thread Swing environ 3 minutes (B1, paragraphe ci-dessous). Corrigé après la recette. | BackgroundRecalculationAcceptanceTest | ba3ec1f |
| Garantie violée : une valeur non nulle pouvait s'afficher `≈ 0` (D1, paragraphe ci-dessous). Corrigé après la recette, preuve dans NeverShownAsZeroAcceptanceTest. | NeverShownAsZeroAcceptanceTest, NeverShownAsZeroProperties | 7e250dd, eaf5e73 |

**B1. Gel de la fenêtre par un carnet hostile : corrigé après la recette, par le calcul hors du thread Swing (E17, [ADR 0014](adr/0014-calcul-hors-interface.md)), preuve dans `BackgroundRecalculationAcceptanceTest`.** Constat de la recette : le recalcul était synchrone sur le thread Swing, sans budget global. Mesuré par le recetteur avec des lignes `sqrt(2^4000+1)+…` de 974 caractères : 3,9 s pour 100 lignes, 11,2 s pour 300. Mesuré par la relecture : 182 s pour 5000 lignes (5,07 Mo), et environ 11,5 s à chaque Entrée à 300 lignes dans une vraie fenêtre. Après correction, Fichier > Ouvrir sur ces 5000 lignes dans la vraie fenêtre rend la main en moins de 300 ms, y compris la création des lignes, désormais par tranches (`NotebookRecalculationTest`) ; les lignes se comptent dans la barre d'état et `Échap` interrompt le calcul. Les 3 minutes de calcul restent, en arrière-plan : le moteur n'a pas été optimisé.

**D1. Une valeur non nulle s'affichait `≈ 0` : corrigé après la recette (E18, [ADR 0004](adr/0004-regles-affichage.md)), preuve dans `NeverShownAsZeroAcceptanceTest` et `NeverShownAsZeroProperties`.** Constat de la recette : `(sqrt(2) / 16) ^ 4096` donnait `≈ 0`, car l'arrondi extérieur ramenait la borne basse à 0, alors que l'exact `(1/16)^4096` était refusé ; de même `√2 - √2 + 1e-40`. Après correction, une borne non nulle qui deviendrait 0 est refusée avec le message de l'exact (`OutwardRounding.java:63`), ce qui retire ces résultats-là, et un intervalle qui contient 0 s'affiche `|x| ≤ b` dans tous les modes : `√2 - √2 + 1e-40` donne `|x| ≤ 4,2 × 10⁻²⁵`.

### Autres défauts consignés

| N° | Gravité | Défaut | Reproduction |
|---|---|---|---|
| D10 | Majeure | Corrigé après la recette, preuve dans `UndoKeepsDraftAcceptanceTest` : Annuler avec une ligne en cours de saisie effaçait la saisie, et Rétablir répondait « Il n'y a rien à rétablir » ; Rétablir la remet maintenant dans la ligne, sans la valider, et `Échap` reste un abandon définitif | Taper du texte sans valider, Ctrl+Z, puis Ctrl+Y : avant, saisie perdue ; après, saisie de retour dans le champ, non validée |
| D11 | Majeure | Sous un petit tas, `OutOfMemoryError` dans le constructeur de la fenêtre : le processus reste vivant, sans fenêtre (`NotebookFrame.java:148-151`, `Main.java:22-38`). Exige un tas non standard : 110 Mo suffisent | Carnet courant de 15,1 Mo avec `-Xmx64m` ; encore vivant, sans fenêtre, après 20 s |
| D13 | Moyenne | Corrigé après la recette, preuve dans `NewKeepsPendingConversionAcceptanceTest` : après une conversion échouée, Fichier > Nouveau sans rien taper faisait abandonner la conversion au démarrage suivant, contrairement au message de `NotebookStore` ; tant que la conversion attend, un carnet vide n'est plus écrit, et seule une ligne validée et écrite y met fin | Historique non convertible, Nouveau, relance : avant, état `NORMAL`, carnet vide ; après, état `MIGRATED`, lignes de l'historique dans le carnet |
| D5 | Faible | Corrigé après la recette, preuve dans `FutureVersionTargetAcceptanceTest` : Enregistrer sous remplaçait un fichier de version future après une confirmation générique ; il est maintenant refusé avant toute question et toute écriture | Enregistrer sous vers un fichier `version: 99` : avant, champ inconnu perdu ; après, refus et fichier identique |
| D6 | Faible | Corrigé après la recette, preuve dans `RoughCentreInsideIntervalAcceptanceTest` : le texte d'un intervalle très large était son centre arrondi à un chiffre, qui pouvait sortir de l'intervalle ; c'est maintenant le centre avec le plus petit nombre de chiffres qui le place entre les bornes | `1,5+(sqrt(2)-sqrt(2))*2e23` : avant, `≈ 2…` hors de l'intervalle ; après, un texte dans l'intervalle |
| D7 | Faible | Corrigé après la recette, preuve dans `NeverShownAsZeroAcceptanceTest` : pour un intervalle contenant 0, Fraction et Auto différaient ; tous les modes donnent maintenant le même texte | `5+(sqrt(2)-sqrt(2))*1e26` : avant, Fraction `≈ 5` et Auto `≈ 0` ; après, `\|x\| ≤ 4,7 × 10¹` partout |
| D9 | Faible | `hs_err_*.log` et `replay_*.log` ne sont pas ignorés par git : le rapport de conformité d'un clone propre indique « + modifications non commitées » | `gradlew conformity` sous manque de mémoire, voir `git status` ; sans plantage, le même rapport indique bien `0bdd5343ecac` seul |
| D14 | Faible | Le message sur les entrées les plus anciennes est inexact (`NotebookStore.java:247`) | 12 000 entrées : annonce 7000 dans `v2.bak`, qui en contient 10 000 ; les 2000 plus anciennes sont seulement dans `v1.bak` |
| D15 | Faible | Répertoire de données invalide : sortie silencieuse, code 0, sans fenêtre (`DataDirectory.java:67`) | `APPDATA=C:\bad\|dir` |
| D16 | Faible | Un collage trop long est tronqué sans message (`LineField.java:116`) | Coller 20 000 caractères : le champ en garde 16 385 |
| D17 | Faible | Une valeur dont la forme canonique dépasse 1000 caractères migre en commentaire coupé à 400 caractères (`HistoryMigration.java:49`) | Entrée d'historique valant `2^4095+1` |
| D8 | Cosmétique | Décimal sous le seuil annoncé de 10⁻¹⁵ (`ValueFormatter.java:132`) | `(sqrt(2)-sqrt(2))*1.21134e9+5.00998982603e-16` |

Un défaut consigné à tort a été retiré : `x = 1`, `x = 2 +`, `x` n'utilise pas l'ancienne valeur en silence, la ligne 3 affiche « Dépend de la ligne 2, qui est en erreur. »

## 5. Risques et préférences

**Risques (plausibles, non reproduits en conditions réelles)**

- R1. CSV : un espace, une espace insécable ou U+0001 avant `=`, et les caractères pleine largeur `＝＠＋－`, ne sont pas neutralisés (`NotebookExporter.java:150`). Le cas ` =1` est même fixé comme non neutralisé par NotebookExporterTest. Les virgules ne sont pas citées, ce qui peut isoler une formule pour un lecteur à séparateur virgule. Seuls les octets ont été vus : à essayer dans Excel (section 8). Un correctif a été écrit puis retiré, car il contredisait ce choix testé sans exploitation prouvée.
- R2. `HistoryCodec.java:184` ne borne pas le nombre de valeurs : un `history.json` de 3 millions de valeurs provoque `OutOfMemoryError` à `-Xmx128m`, et se convertit normalement à partir de 256 Mo.
- R3. Pas de `fsync` du répertoire ni des sauvegardes (lecture du code seulement).
- R4. Deux instances sur le même document : le dernier qui enregistre gagne. Les deux annoncent « enregistré », même si la seconde est en lecture seule pour son propre carnet.
- R5. Levé : les 4 JVM de PIT mortes faute de mémoire native venaient de la charge de la machine. Remesuré sans autre charge : aucun `hs_err_pid*`, score 91,7 % contre 91,6 %.
- R6. Hors du cas hostile B1, les plus gros fichiers ordinaires gèlent la fenêtre 2,2 à 2,7 s à l'ouverture et environ 2 s au démarrage. Les lectures et écritures de fichiers restent synchrones : le temps sur un disque lent ou réseau n'est pas mesuré. La logique, elle, n'est pas restée dans la vue, qui n'importe que des types d'affichage.
- R7. Aucun gestionnaire d'exception non capturée : une exception simulée dans un dialogue ne laisse qu'une trace sur stderr. La fenêtre a survécu et le brouillon a été conservé.
- R8. Un fichier compact de 16,75 Mo est accepté, puis la première modification échoue à l'enregistrement automatique (« dépasse 16 Mio ») ; le statut le dit.
- R9. Quitter est refusé au-delà de la limite d'une ligne, ou à la ligne 5001, sans indiquer qu'Échap débloque. Échap vérifié au-delà de la limite, pas à la ligne 5001.
- R11. Après une conversion en carnet qui échoue, le démarrage suivant repart de `history.json` : le message n'annonce plus les lignes rejetées au premier passage, qui restent dans `history.tsv.v1.bak` (lecture du code seulement).
- R10. L'adoption de `~/.calc` copie sans atomicité (`JsonHistoryStore.java:238`) : une copie tronquée donne un carnet réduit, et le fichier complet n'est jamais repris.

**Préférences**

- Identifiants de ligne réutilisés après un rechargement (sans effet observé).
- Supprimer une définition résout de nouveau variables et `ans` par position (ADR 0008).
- L'annonce des lignes rejetées garde la tonalité de succès du message de migration, comme l'attendent les tests de recette.
- Windows sans `APPDATA` : la version 1 utilisait `~/.calc`, qui n'est pas adopté sur ce système.
- `1^4097`, `0^4097` et `(-1)^4097` sont refusés, comme documenté.

## 6. Écarts entre documentation et réalité

Environ 90 affirmations du README et 105 du guide utilisateur ont été contrôlées (ordre de grandeur, non décomptées une à une). Écarts :

- `README.md:104` : fichier abîmé « jamais écrasé ». En réalité, il est remplacé à la première ligne validée, le `.bak` étant conservé. Trompeur.
- `README.md:102`, `docs/GUIDE-UTILISATEUR.md` : « jamais 0 ». Écart levé par la correction de D1.
- `README.md:105`, guide:114 : migration « sans perte », une ligne par calcul. Au-delà de 5000 entrées, les plus anciennes restent seulement dans la sauvegarde ; certaines deviennent des commentaires, et d'autres sont rejetées, ce que le statut annonce désormais.
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

Exactitude, borne certifiée, modes d'affichage et réutilisation sans perte tiennent dans tous les scénarios prévus. La relecture complète du noyau a trouvé corrects les sens d'arrondi, la racine et les limites vérifiées avant calcul (15 000 puis 20 000 essais aléatoires sans échec). Elle a aussi trouvé un défaut bloquant, corrigé : la borne affichée pouvait exclure la vraie valeur pour un intervalle très large. Un second défaut bloquant, D1, a été corrigé après la recette : une valeur non nulle ne s'affiche plus jamais 0. Une valeur trop petite pour être encadrée sans toucher 0 est refusée, et une valeur de signe inconnu s'affiche `|x| ≤ b`, sous réserve du contrôle manuel de la section 8.
### Idée 2 : carnet de calcul — livrée avec réserves

Expressions, références stables, annuler et rétablir, documents, sauvegarde automatique, exports et migration de tous les fichiers de référence passent de bout en bout, et 120 000 pas contre un oracle n'ont trouvé aucune redirection de référence. Les pertes de données trouvées ont été corrigées : alias du carnet courant, fichier temporaire, lignes d'historique légitimes reprises. Les autres lignes rejetées ne sont pas dans le carnet, mais elles sont maintenant annoncées et restent dans la sauvegarde. Le défaut bloquant B1, un carnet hostile qui figeait la fenêtre environ 3 minutes, a été corrigé après la recette par le calcul hors du thread Swing, sous réserve du contrôle manuel de la section 8. D13, une conversion échouée que Fichier > Nouveau faisait abandonner, a aussi été corrigé après la recette. Reste le risque R1, à éprouver dans Excel avant de présenter la neutralisation CSV comme une garantie de sécurité complète.

### Idée 3 : fiabilité prouvée — livrée avec réserves

Build Gradle, JUnit 5, tests par propriétés et de robustesse, tests d'interface, seuils de mutation, rapport généré, JAR reproductible et paquet Windows ont tous été exécutés ici avec succès. Mais ces tests verts n'ont vu aucun des défauts bloquants de la section 4, que seule la relecture a trouvés : leur couverture ne vaut pas preuve d'absence de défaut. La CI du dernier commit de `main` n'a pas démarré (facturation du compte), macOS et Linux n'ont été vus qu'à la CI verte de `137d4cb`, et la chaîne de release n'a **jamais tourné** : aucune archive, aucun SHA256SUMS ni SBOM publié n'existe. Cette dernière partie de la promesse est écrite mais non démontrée.

## 8. Check-list manuelle

- [ ] Rétablir la facturation GitHub Actions, puis vérifier une CI verte sur `main` avec les tests d'interface exécutés sur les 3 OS.
- [ ] Lancer le workflow Release une première fois (pré-version), en décider le numéro.
- [ ] Parcours complet au clavier sur Windows (Tab, flèches, Entrée, Ctrl+Z/Y, menus, dialogues de fichiers réels).
- [ ] Essai de l'application sur macOS et Linux (raccourcis, dialogues natifs).
- [ ] Ouverture d'un CSV exporté dans Excel : séparateur, accents, et cellules ` =1+1`, `＝1+1`, `1,=1+1` (risque R1).
- [ ] Lisibilité des badges exact et approché, en clair et selon le thème du système.
- [ ] Téléchargement d'une release et vérification des SHA-256 avec SHA256SUMS, lecture du SBOM.
- [ ] Avertissements SmartScreen (Windows) et Gatekeeper (macOS) au premier lancement.
- [ ] Ouvrir un carnet de 5000 lignes hostiles : la fenêtre se déplace et les menus s'ouvrent pendant le calcul, la barre d'état compte les lignes.
- [ ] Pendant ce calcul, appuyer sur Échap : le calcul s'arrête et les lignes restantes disent « Non calculée : calcul interrompu ».
- [ ] Taper `√2 - √2` dans la fenêtre : le résultat se lit `|x| ≤ …` et ne contient pas `≈ 0`.
- [ ] Taper `(sqrt(2) / 16) ^ 4096` : la ligne est en erreur avec le même message que `(1/16) ^ 4096`.

## 9. Recommandations hiérarchisées

Classées par criticité, puis impact, puis coût.

| Rang | Recommandation | Criticité | Impact | Coût |
|---|---|---|---|---|
| 1 | Rétablir la CI et exécuter une première release | Haute | Prouve la partie non démontrée de l'idée 3 | Faible |
| 2 | Contrôler à la main le calcul hors du thread Swing et le texte `\|x\| ≤ b` (section 8) | Haute | Confirme les corrections de B1 et D1 dans une vraie fenêtre | Faible |
| 3 | Éprouver R1 dans Excel, puis élargir la neutralisation si besoin | Haute | Sécurité des exports | Faible |
| 4 | Rien à faire pour D13, corrigé (`NewKeepsPendingConversionAcceptanceTest`) | Moyenne | Confiance dans la migration | Aucun |
| 5 | Fait après la recette, preuve dans `UndoKeepsDraftAcceptanceTest` : conserver une ligne en cours de saisie à l'annulation (D10) | Moyenne | Évite la perte d'une saisie | Faible |
| 6 | Mettre à jour README, guides, `CLAUDE.md` (section 6) ; ignorer `hs_err_*` (D9) | Moyenne | Documentation fiable | Faible |
| 7 | Gérer les erreurs au démarrage (D11, D15, R7), borner `HistoryCodec` (R2) | Faible | Marge de sûreté | Faible |
