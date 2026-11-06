# Journal des modifications

Ce fichier suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Aucune version n'a encore été publiée :
tout ce qui suit est dans la prochaine version. Le numéro sera choisi au moment du tag `vX.Y.Z` (voir
[docs/RELEASE.md](docs/RELEASE.md)).

## [Non publié]

### Ajouté

- **Un carnet de calcul** comme fenêtre principale : une expression par ligne, avec variables (`prix = 12,5`), `ans` (le
  dernier résultat) et `$n` (le résultat de la ligne n), commentaires, annuler / rétablir, renumérotation automatique des
  références quand on insère, déplace ou supprime des lignes, sauvegarde automatique.
- **Un langage d'expressions** : `+ - * /`, `^`, `√`, parenthèses, virgule ou point décimal, notation scientifique,
  espaces insécables pour les milliers, messages d'erreur positionnés (la partie fautive est soulignée).
- **Documents et exports** : fichiers `.calc.json` (menu Fichier : Nouveau, Ouvrir, Enregistrer, Enregistrer sous),
  exports Markdown et CSV (neutralisation des formules de tableur).
- **Modes d'affichage** : Auto, Décimal, Fraction, Scientifique, sans recalcul, avec un badge « Exact » / « ≈ Approché ».
- **Releases multiplateformes** : applications autonomes pour Windows, macOS (puce Apple) et Linux, sommes SHA-256 et
  nomenclature logicielle CycloneDX, publiées automatiquement sur un tag `vX.Y.Z`.
- **Fiabilité mesurée et publiée** : couverture de code (JaCoCo) et tests de mutation (PIT) avec des seuils par package,
  rapport de conformité généré à partir des résultats réels (`gradlew conformityReport`), publié par la CI et joint à chaque
  release.
- Documentation : README, [architecture](docs/ARCHITECTURE.md), [guide de l'utilisateur](docs/GUIDE-UTILISATEUR.md),
  [guide des expressions](docs/GUIDE-EXPRESSIONS.md), [stratégie de test](docs/TESTS.md), décisions d'architecture
  ([docs/adr](docs/adr/README.md)).

### Modifié

- **Calcul en arrière-plan** : la fenêtre répond pendant le calcul d'un carnet, même de 5000 lignes coûteuses. Une ligne pas
  encore calculée affiche « Calcul en cours… », la barre d'état compte les lignes, et `Échap` interrompt le calcul quand aucune
  saisie n'est en cours.
- **Moteur exact** : les calculs utilisent des fractions exactes (`1 / 3` puis `× 3` redonne exactement `1`) ; une racine
  non exacte est un intervalle qui contient certainement la vraie valeur, avec sa borne d'erreur. Remplace l'arrondi à
  12 décimales de la version 1 (écarts justifiés dans [docs/changements-comportement-E06.md](docs/changements-comportement-E06.md)).
- **Persistance** : le carnet est un fichier JSON versionné (v3) qui ne garde que les textes des lignes ; écriture
  atomique, verrou pour qu'une seule fenêtre écrive, fichier d'une version plus récente en lecture seule.
- Le build passe de Maven à Gradle (Kotlin DSL, JDK 21, JUnit 5 et jqwik).

### Supprimé

- L'ancien formulaire à deux champs (deux nombres et une opération) : chacune de ses fonctions a son équivalent dans le
  carnet (tableau dans [docs/adr/0011-fichiers-dans-l-interface.md](docs/adr/0011-fichiers-dans-l-interface.md)).

### Corrigé

- **Annuler ne perd plus la saisie en cours.** Annuler retire de la ligne une saisie non validée, et Rétablir l'y remet, sans la
  valider, tant que rien d'autre n'a changé. `Échap` reste un abandon définitif.
- **Une valeur non nulle n'est plus jamais affichée 0.** Une valeur dont le signe n'a pas pu être établi s'affiche `|x| ≤ b`
  dans tous les modes, à la place de l'ancien texte ; une valeur trop petite pour être encadrée sans toucher 0 est refusée,
  avec le même message que sa version exacte (`(sqrt(2) / 16) ^ 4096` comme `(1/16) ^ 4096`).

- **Fichier > Nouveau n'abandonne plus une conversion d'historique en échec.** Quand la conversion de l'historique a échoué,
  elle reprend bien au démarrage suivant tant que rien n'a été saisi : Nouveau, un changement de mode ou Enregistrer sous d'un
  carnet vide n'écrivent plus de carnet courant vide. La première ligne validée et écrite met fin à la conversion, même si
  elle est effacée ensuite.

### Sécurité

- Entrées bornées avant tout calcul (taille des nombres, profondeur, longueur des lignes, taille des fichiers) ; fichiers
  hostiles refusés avec une cause sans jamais remplacer le fichier existant.
- Les applications publiées **ne sont pas signées** (Windows SmartScreen et macOS Gatekeeper avertissent au premier
  lancement) ; les sommes SHA-256 vérifient le fichier, pas son éditeur.
