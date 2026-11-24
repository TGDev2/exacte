# 0016 - Système de design : des jetons dans un fichier .properties versionné, des sorties générées et versionnées

## Status (Statut)

Accepted. Date: 2026-11-11.

## Context (Contexte)

Le bureau (Swing) et la page web (`web/site`) doivent partager un seul système de design : les mêmes couleurs, les mêmes tailles, les mêmes polices, en clair et en sombre.

Jusqu'ici, les couleurs étaient des constantes Java : `calc.ui.widget.Palette` (états, badge d'exactitude) et `calc.ui.notebook.NotebookPalette` (lignes du carnet), plus deux gris écrits en dur (bordure des champs, filet de la barre d'état). La page web avait ses propres couleurs dans `style.css`. Rien ne garantissait que les deux restent d'accord, ni qu'un thème sombre garde les contrastes.

Le bureau n'a pas d'analyseur JSON et le projet n'ajoute pas de dépendance pour cela. FlatLaf, prévu pour l'apparence du bureau, lit ses thèmes dans des fichiers `.properties`. Le site est copié tel quel par la tâche `:web:site` et ne doit pas dépendre de la construction du bureau.

## Decision (Décision)

`src/main/resources/calc/ui/design/tokens.properties` est la seule source des jetons. Il déclare les couleurs par rôle et par thème (`color.light.<rôle>`, `color.dark.<rôle>`, les mêmes rôles dans les deux), les familles de polices (`font.ui.family=Inter`, `font.mono.family=JetBrains Mono`), les tailles (`font.size.*`), les espacements (`space.*`, sur une grille de 4 px), les rayons (`radius.*`) et les paires de contraste mesurées (`contrast.text.*`, au moins 4,5:1, et `contrast.ui.*`, au moins 3:1, dans les deux thèmes).

`calc.ui.design.DesignTokens` lit ce fichier à l'exécution (`load()`, `color(Theme, rôle)`, `size`, `fontFamily`, `contrastPairs()`) et le vérifie : un fichier mal formé échoue tout de suite, jamais par une mauvaise couleur plus tard. `calc.ui.design.Theme` est l'énumération `LIGHT` / `DARK`. Le paquet ne dépend d'aucun autre paquet de l'application.

`Palette` et `NotebookPalette` n'ont plus aucun littéral de couleur : elles lisent les jetons du thème actif, le clair tant que le choix d'un thème n'existe pas.

Un générateur, `calc.ui.design.DesignTokenGenerator` (jeu de sources de test, lancé par la tâche Gradle `generateDesignTokens`), écrit trois fichiers versionnés : les thèmes FlatLaf `src/main/resources/calc/ui/theme/CalcLightLaf.properties` et `CalcDarkLaf.properties`, et `web/site/tokens.css`. `DesignTokensAcceptanceTest` échoue si l'un d'eux diffère de la sortie du générateur, comme `CharacterCompat` et son test de parité.

La direction des couleurs est celle d'une feuille qui calcule, d'après le cahier d'écolier réglé : en clair, un papier à peine bleuté, une encre bleu-noir, le bleu de la réglure pour le focus et l'accent, le rouge de la marge pour les erreurs ; en sombre, une ardoise bleu nuit avec les mêmes teintes éclaircies. Un résultat exact reste discret (un vert doux), un résultat approché se voit par une teinte ocre.

## Alternatives considered (Alternatives considérées)

- Des jetons en JSON : il faut un analyseur côté bureau, donc une dépendance ou un analyseur de plus à maintenir. Rejeté.
- Générer les fichiers dans `processResources` : la génération dépend alors des classes de test, ce qui crée un cycle, et le site web dépend de la construction du bureau. Rejeté : les sorties sont versionnées et leur synchronisation est vérifiée par un test.
- Écrire les couleurs à la main à deux endroits (Java et CSS) : rien ne garantit qu'ils restent d'accord. Rejeté.
- Garder des constantes `Color` dans `Palette` et `NotebookPalette` : un thème sombre aurait demandé une seconde série de constantes, sans source commune avec le web. Rejeté.

## Consequences (Conséquences)

- Changer une couleur, c'est modifier `tokens.properties` puis lancer `gradlew generateDesignTokens` ; `check` échoue tant que les fichiers générés ne sont pas à jour.
- Les contrastes sont prouvés pour chaque paire déclarée, dans les deux thèmes ; une nouvelle couleur de texte doit apparaître dans au moins une paire `contrast.text.*`.
- Les thèmes FlatLaf existent comme texte avant que FlatLaf ne soit une dépendance ; leur chargement viendra avec FlatLaf (ADR 0017).
- `web/site/tokens.css` est copié par `:web:site` et lié par `index.html` avant `style.css`, qui ne lit ses couleurs que par `var(--color-…)` : la page suit le thème du système par `prefers-color-scheme`.
- Le thème actif est fixé au clair dans `Palette` ; le choix du thème par l'utilisateur reste à faire.
- Les captures de `docs/images/avant` montrent l'interface d'avant ces couleurs ; elles ne sont pas régénérées.
