# 0015 - La version web : le même cœur, compilé vers JavaScript par TeaVM

## Status (Statut)

Accepted. Date: 2026-11-07. Le brief demandait le numéro 0014, déjà pris par [l'ADR 0014](0014-calcul-hors-interface.md) : celui-ci est le 0015.

## Context (Contexte)

Calc est une application de bureau. Pour que le projet soit jugé en quelques minutes, sans installation, il faut montrer le carnet
dans une page publique (GitHub Pages) : `(1/3)*3` vaut exactement `1`, `√2` s'affiche avec sa borne d'erreur, et les `$n` sont
renumérotés quand une ligne bouge. La promesse du projet est la fiabilité : la page doit donc donner exactement les mêmes
résultats que le bureau, et le prouver, pas seulement y ressembler. Le cœur (`calc.number`, `calc.format`, `calc.expression`,
`calc.notebook`) ne dépend ni de Swing ni d'un fichier, et ne devait pas être modifié (`src/main/java` reste identique).

## Decision (Décision)

Nous compilons le cœur existant vers JavaScript avec TeaVM, et nous prouvons la parité avec la JVM par des tests.

1. **TeaVM 0.16.0** (plugin Gradle `org.teavm`, cible JavaScript, module UMD) dans un sous-projet Gradle `web/` qui dépend du code
   principal sans le modifier. TeaVM ne compile que ce qui est atteignable depuis `calc.web.WebExports`, seul point d'entrée : le
   code Swing, la persistance et l'écriture atomique ne sont jamais atteints.
2. **Un carnet en Java pur.** `calc.web.WebNotebook` pilote un `NotebookEditor` et renvoie chaque fois un instantané JSON, une chaîne
   identique sur la JVM et sous JavaScript. Toute la mise en forme passe par `ValueFormatter` : la page n'affiche que les chaînes de
   l'instantané, et ne calcule ni ne formate rien. Le carnet se teste donc sur la JVM, dans `check`.
3. **Compléter la bibliothèque de TeaVM plutôt que modifier le cœur.** Deux méthodes du JDK posaient problème, et le bytecode du cœur
   reste celui de javac. Une extension TeaVM (`calc.web.compat.CompatPlugin`) réécrit les appels dans les classes `calc.*` au moment où
   TeaVM les lit :
   - `BigInteger.sqrtAndRemainder`, absente de `TBigInteger` en 0.16.0 alors que `sqrt()` y existe : `BigIntegerCompat` calcule
     `r = x.sqrt()` et le reste `x - r²` ;
   - `Pattern.compile` : un motif qui compte une classe de caractères dans un groupe répété, comme `(?:\d{3})+`, refuse
     `000000` sous TeaVM. `PatternCompat` écrit `\d{3}` sous la forme équivalente `\d\d\d`. Sans lui, `1 000 000` (espaces insécables)
     aurait été refusé dans le carnet. Ce défaut ne se voyait pas à la compilation : seul le rejeu du golden l'a révélé.

   Au plus 3 méthodes du JDK sont complétées, 2 le sont. S'y ajoute une exception nommée, la classification Unicode : `CharacterCompat`
   reçoit les 7 méthodes à argument `int` que le `Lexer` utilise pour classer un code point, avec une table générée depuis le JDK 21 de la
   chaîne d'outils (`.\gradlew.bat :web:generateCharacterCompat`). `CharacterCompatParityTest` la compare au JDK sur les 0x110000 code
   points. La JVM reste la référence.
4. **La parité est un test, et il bloque le déploiement.** `:web:webTest` rejoue sous Node, sur `calc.js`, les 70 cas du golden, un carnet
   d'essai, puis un corpus de 1074 carnets dans les 4 modes d'affichage, comparé à `WebNotebook` sur la JVM. Une seule chaîne
   différente fait échouer la tâche, avec la graine, le carnet, le mode et les deux valeurs. Aucune liste de tolérance n'existe.
   Un second jeu de tests ouvre la page dans un navigateur sans affichage (Edge ou Chrome).
5. **Un workflow distinct, `pages.yml`**, lancé lors d'un `push` sur la branche `main` ou à la demande. Il construit le rapport de
   conformité, lance `webTest`, assemble le site (la page et le rapport de conformité) et le déploie sur GitHub Pages, depuis `main` seulement.
   `ci.yml` et `release.yml` ne changent pas.
6. **Sauvegarde dans le navigateur.** La page garde les textes des lignes et le mode dans `localStorage` (clé `calc.web.notebook.v1`),
   jamais un résultat, comme le bureau. L'historique d'annulation est perdu au rechargement.

Mesures relevées dans [l'essai](../essais/teavm-essai.md) (Windows 11, Java 21, Node v22.14.0, 2026-11-07) :

- golden sous Node : `OK 70/70` (55 OK, 15 ERROR) ;
- `calc.js` : 405 712 octets avec les deux compléments, 449 659 octets avec la table de `CharacterCompat` ;
- corpus : 1074 carnets (`export`, 23 `hand-*`, 1000 `line-*`, 50 `notebook-*`), 0 écart avec les graines 20261107 et 10000000000 ;
- `webTest` complet : 17 s environ, la compilation par TeaVM ajoutant environ 5 s.

## Alternatives considered (Alternatives considérées)

- **CheerpJ**, qui exécuterait le JAR tel quel. Écarté : le palier gratuit impose le réseau de distribution de l'éditeur et une mention,
  le démarrage prend quelques secondes et tout le JAR est servi, Swing compris.
- **Réécrire le calcul en TypeScript.** Écarté : deux implémentations à garder identiques, ce qui est le contraire du but. La page
  montrerait une copie du calcul, pas le calcul.
- **Remplacer `OutwardRounding` par une copie dans `web/`** pour éviter `sqrtAndRemainder`. Écarté : deux sources du même calcul.
- **Modifier le cœur.** Écarté : le complément côté `web/` suffit, et le cœur reste celui qui est testé et publié.
- **Mettre Node et le navigateur dans `check`.** Écarté : `check` doit rester court et vert sur trois systèmes, sans Node. `check` couvre
  `calc.web` sur la JVM, `webTest` est lancé par `pages.yml`.
- **Réutiliser `ci.yml` pour déployer.** Écarté : un test (`CiTriggersTest`) impose son lancement à la demande seulement, et un
  déploiement depuis `main` exige un déclencheur `push`.
- **Un Web Worker pour le calcul.** Écarté pour l'instant, voir les limites ; à reconsidérer si elles gênent.
- **Réutiliser le format `.calc.json`.** Écarté : `NotebookCodec` tire `java.nio` et `java.util.logging`, et les fichiers sont hors périmètre.

## Consequences (Conséquences)

Ce qui devient plus facile : n'importe qui essaie le carnet dans un navigateur, et la page est comparée au bureau à chaque déploiement,
avec un rapport de conformité servi à côté d'elle.

Limites de la version web, écrites aussi dans le README :

- **Calcul synchrone (D7).** Le calcul s'exécute sur le fil principal de la page. Le cœur ne s'interrompt qu'entre deux lignes, et un
  carnet hostile de 5000 lignes demande 182 s sur la JVM ([docs/RECETTE.md](../RECETTE.md)) : un carnet très lourd peut figer l'onglet.
  Le bureau, lui, calcule en arrière-plan et peut interrompre ([ADR 0014](0014-calcul-hors-interface.md)).
- **Pas de fichier.** Pas de menu Fichier, pas d'ouverture ni d'enregistrement de fichiers `.calc.json`, pas de migration des anciens
  historiques, pas de verrou ni d'écriture atomique. Le carnet reste dans ce navigateur, et un autre navigateur ou une navigation privée
  ne le retrouve pas.
- **Unicode de TeaVM.** Le fichier de classification de TeaVM 0.16.0 est plus récent que celui du JDK 21 : sans `CharacterCompat`, U+A7CB
  et U+2EBF0 formaient un nom de variable sous Node alors que la JVM les refuse, et un carnet tiré au hasard (`notebook-36`, U+0984) a
  montré le même écart. Les 7 méthodes complétées couvrent la classification que le `Lexer` utilise ; la table dépend du JDK 21, et un
  autre appel de classification Unicode dans le cœur ne serait pas couvert. La ligne « Parité Unicode » de
  [l'essai](../essais/teavm-essai.md) donne le constat et les cas.
- **Une expression régulière est un risque.** `PatternCompat` ne réécrit que `\d{3}` : tout nouveau motif du cœur qui compte une classe
  de caractères dans un groupe répété demande les tests de parité, et peut-être un complément.
- **Limite du rapport de conformité.** Le rapport ne lit que les résultats de la racine (`build/test-results/test`). Les tests de
  `web/src/webTest` écrivent ailleurs et ne sont pas lus : la garantie G29 cite des classes de la racine, et la comparaison sous Node
  elle-même est prouvée par la réussite de `pages.yml`, pas par une ligne du rapport. Le corpus est tiré d'une graine (20261107 par défaut,
  `github.run_id` en CI) : une graine donne un corpus fixe, une autre en explore un autre, et aucune n'établit l'absence d'écart.
- **Ce qui reste non vérifié.** La page n'a été essayée que dans Edge sans affichage sur le poste de développement ; la prise en charge
  d'un téléphone ou d'un autre navigateur n'est pas mesurée. Le premier déploiement suppose que le propriétaire active Pages
  (Paramètres → Pages → Source : GitHub Actions).
