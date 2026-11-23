# 0017 - FlatLaf 3.x et polices Inter et JetBrains Mono embarquées

## Status (Statut)

Accepted. Date: 2026-11-23.

## Context (Contexte)

Jusqu'ici, le bureau prenait l'apparence du système (`UIManager.getSystemLookAndFeelClassName()`) et les lignes du carnet la police logique `Monospaced`. Sous Windows, cette apparence est datée ; il n'existe pas de thème sombre ; le rendu change d'une plate-forme à l'autre, comme les polices réellement utilisées derrière `Dialog` et `Monospaced`.

Les jetons de l'ADR 0016 fixent déjà deux familles (`font.ui.family=Inter`, `font.mono.family=JetBrains Mono`) et deux thèmes FlatLaf générés (`CalcLightLaf.properties`, `CalcDarkLaf.properties`), mais rien ne les appliquait encore : il manquait FlatLaf lui-même et les polices.

Le projet n'avait jusque-là aucune dépendance d'exécution (ADR 0012) : en ajouter une engage à suivre ses versions, sa licence, sa place dans le SBOM et le poids qu'elle ajoute à chaque archive.

## Decision (Décision)

Nous ajoutons trois dépendances `implementation`, les seules dépendances d'exécution du projet, prises sur Maven Central le 2026-11-23 (dernières versions publiées, lues dans `maven-metadata.xml`) :

- `com.formdev:flatlaf:3.8`, la dernière version stable 3.x : une apparence Swing moderne, maintenue, avec un thème clair et un thème sombre, qui lit ses thèmes dans des fichiers `.properties` (ceux que génère l'ADR 0016) et gère les écrans à haute densité. Licence Apache 2.0.
- `com.formdev:flatlaf-fonts-inter:4.1` : la police Inter, police de l'interface, empaquetée par les auteurs de FlatLaf avec une méthode `FlatInterFont.install()` qui l'enregistre auprès de l'environnement graphique. Le code est sous Apache 2.0, la police sous SIL Open Font License 1.1 (OFL).
- `com.formdev:flatlaf-fonts-jetbrains-mono:2.304` : la police JetBrains Mono, pour ce que l'utilisateur tape et pour les résultats, avec `FlatJetBrainsMonoFont.install()`. Le code est sous Apache 2.0, la police sous SIL Open Font License 1.1 (OFL).

Dans le paquet `calc.ui.theme` :

- `CalcLightLaf` (sous-classe de `FlatLightLaf`, nom « Calc clair ») et `CalcDarkLaf` (sous-classe de `FlatDarkLaf`, nom « Calc sombre ») : FlatLaf charge de lui-même le `.properties` du même nom dans le même paquet, donc les couleurs des jetons (`Panel.background` vaut `surface.base` dans chaque thème).
- `ThemeManager.installFonts()` enregistre les deux polices et en fait les familles préférées de FlatLaf (`setPreferredFontFamily(Inter)`, `setPreferredMonospacedFontFamily(JetBrains Mono)`). `ThemeManager.apply(Theme)` installe le thème clair ou sombre et redessine les fenêtres déjà ouvertes (`FlatLaf.updateUI()`).
- `Palette.activeTheme()` (et donc `NotebookPalette`) lit le thème actif dans l'apparence installée (`FlatLaf.isLafDark()`) : il n'y a qu'une source, l'apparence que `ThemeManager` a posée.

`Main` appelle `installFonts()` puis `apply(Theme.LIGHT)` sur le fil de l'interface, avant de construire la fenêtre ; le choix mémorisé d'un thème viendra ensuite. Le champ d'une ligne, son résultat et la boîte d'aide (syntaxe, raccourcis alignés en colonnes, à propos) sont en JetBrains Mono ; tout le reste, numéro et message d'erreur d'une ligne compris, garde la police par défaut, Inter. Les tailles relatives restent celles d'avant (le champ à un point de plus que les champs de l'apparence).

Les importations de FlatLaf restent dans `calc.ui.*`. `calc.jar` ne contient aucune classe de FlatLaf : les trois jars restent séparés, copiés à côté de lui par `stageAppInput`.

Licences : les textes complets sont pris dans les jars téléchargés (`META-INF/LICENSE` de `flatlaf-3.8.jar`, `com/formdev/flatlaf/fonts/inter/LICENSE.txt`, `com/formdev/flatlaf/fonts/jetbrains_mono/OFL.txt`) et versionnés dans `config/licences/` : `FlatLaf-LICENSE.txt` (Apache License 2.0), `Inter-OFL.txt` et `JetBrainsMono-OFL.txt` (SIL Open Font License 1.1). `stageAppInput` les copie dans `licences/` de l'entrée de `jpackage`, donc dans `app/licences/` de chaque image, et `tools/release/smoke-test.sh` échoue si l'un des trois y manque.

SBOM : `gradlew cyclonedxBom` écrit `build/reports/cyclonedx/bom.json`, qui liste maintenant les trois composants avec leurs licences : `com.formdev:flatlaf:3.8` (Apache-2.0), `com.formdev:flatlaf-fonts-inter:4.1` et `com.formdev:flatlaf-fonts-jetbrains-mono:2.304` (Apache-2.0 et SIL Open Font License 1.1), vérifié le 2026-11-23.

Taille mesurée de l'archive Windows `Calc-1.0.0-windows-x64.zip` (`gradlew packageApp -PappVersion=1.0.0`, même poste, même JDK) :

| Archive Windows | Taille |
|---|---|
| Avant (code de `main`, apparence du système) | 35 986 226 octets |
| Après (FlatLaf, Inter, JetBrains Mono, licences) | 39 497 958 octets |

La hausse est de 3 511 732 octets, soit 9,8 %. Elle vient surtout des deux polices. `jdeps` ajoute le module `java.logging`, dont FlatLaf a besoin ; la liste calculée devient `java.base,java.desktop,java.logging,java.prefs`, plus `jdk.localedata` ajouté à la main (ADR 0012). Deux constructions de `calc.jar` (`gradlew jar --rerun-tasks`) ont donné le même SHA-256.

## Alternatives considered (Alternatives considérées)

- Garder l'apparence du système : aucune dépendance, mais un rendu daté sous Windows, différent sur chaque plate-forme, et pas de thème sombre. Rejeté.
- Nimbus, livré avec le JDK : pas de thème sombre maintenu, pas de polices, une personnalisation par des clés peu documentées. Rejeté.
- Des fichiers de police TTF copiés dans le dépôt : pas d'artefact versionné, une licence à suivre à la main et un code d'enregistrement à écrire. Rejeté au profit des artefacts officiels `flatlaf-fonts-*`.

## Consequences (Conséquences)

- Le projet a trois dépendances d'exécution à suivre : une nouvelle version de FlatLaf ou d'une police se reporte dans `build.gradle.kts`, ses textes de licence dans `config/licences/` (pris dans le nouveau jar) et sa présence se vérifie dans le SBOM.
- Chaque archive pèse environ 3,5 Mo de plus.
- `ModeShortcutConflictTest` et `NotebookShortcutConflictTest` ne parcourent que les apparences installées dans le JDK ; `FlatLafFoundationAcceptanceTest` vérifie en plus que ni le thème clair ni le thème sombre ne lient Ctrl+M ni les raccourcis du carnet.
- La netteté du rendu à 150 % et 200 % sous Windows reste un contrôle manuel.
- `gradlew screenshots` construit la fenêtre comme `Main` : polices embarquées et thème clair. Les images `docs/images/avant`, prises avant cette décision, gardent l'apparence du système. Le générateur ouvre désormais son carnet d'un coup, depuis le magasin, au lieu d'entrer les lignes une à une : un calcul par ligne entrée gardait en vie environ 1 500 générations de son carnet de 1 500 lignes, plus de 400 Mo sous Metal comme sous FlatLaf, au bord du tas de 512 Mo des tests.
- Le menu Thème, le thème « Système », le titre de la fenêtre et la refonte des lignes s'appuieront sur `ThemeManager` et les deux familles de polices.
