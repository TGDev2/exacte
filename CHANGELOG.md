# Journal des modifications

Ce fichier suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). La section de chaque version publiée sert
aussi de notes de version sur GitHub (voir [docs/RELEASE.md](docs/RELEASE.md)).

## Version web (déployée depuis `main`, hors versions numérotées)

La page est publiée par `pages.yml` à chaque `push` sur `main` ([docs/CI.md](docs/CI.md#le-déploiement-de-la-page-pagesyml)) : elle
n'a pas de numéro de version et n'est pas jointe aux releases.

### Ajouté

- **Calc dans le navigateur** : https://tgdev2.github.io/Calculatrice/. Le même cœur, compilé vers JavaScript par TeaVM sans modifier
  `src/main/java`, donne le carnet, les références, les variables, les quatre modes d'affichage, annuler et rétablir. Le carnet
  reste dans le navigateur ([ADR 0015](docs/adr/0015-version-web-teavm.md)).
- **Une garantie G29** : la version web donne les mêmes résultats que le bureau, comparée sous Node par `webTest` (70 cas du golden,
  un corpus de 1074 carnets dans les 4 modes), ce qui bloque le déploiement ([docs/TESTS.md](docs/TESTS.md#la-version-web-g29)).
- **Le rapport de conformité** est servi à côté de la page.

### Limites

- Pas de menu Fichier, de fichiers `.calc.json`, de migration des anciens historiques, de verrou ni d'écriture atomique.
- Le calcul se fait sur le fil principal : un carnet très lourd peut figer l'onglet.
- La classification Unicode de TeaVM est complétée pour coller au JDK 21 ; le rapport de conformité ne lit que les résultats de la racine.

## [Non publié]

## [1.0.0] - 2026-11-07

### Ajouté

- **Un carnet de calcul** comme fenêtre principale : une expression par ligne, avec des variables (`prix = 12,5`), `ans` (le
  dernier résultat) et `$n` (le résultat de la ligne n), des commentaires, annuler / rétablir et une sauvegarde automatique.
  Les références se renumérotent toutes seules quand on insère, déplace ou supprime des lignes.
- **Des expressions** : `+ - * /`, `^`, `√`, parenthèses, virgule ou point décimal, notation scientifique. Quand une expression
  est fausse, la partie fautive est soulignée (voir le [guide des expressions](docs/GUIDE-EXPRESSIONS.md)).
- **Des calculs exacts** : `1 / 3` puis `× 3` redonne exactement `1`. Une racine non exacte est donnée sous forme d'intervalle
  qui contient certainement la vraie valeur, avec sa marge d'erreur.
- **Des documents et des exports** : fichiers `.calc.json` (menu Fichier : Nouveau, Ouvrir, Enregistrer, Enregistrer sous),
  exports Markdown et CSV. Dans le CSV, la neutralisation partielle des formules de tableur ne remplace pas la prudence :
  n'ouvrez pas dans un tableur un export dont vous ne connaissez pas le contenu.
- **Des modes d'affichage** : Auto, Décimal, Fraction et Scientifique, sans recalcul, avec un badge « Exact » ou « ≈ Approché ».
- **Calcul en arrière-plan** : la fenêtre reste utilisable pendant le calcul d'un gros carnet, même de 5000 lignes coûteuses.
  Une ligne pas encore calculée affiche « Calcul en cours… » et `Échap` interrompt le calcul.
- **Conversion de l'ancien historique** : au premier lancement après une ancienne version, l'historique est converti en
  carnet, et une copie de sauvegarde de l'ancien fichier est gardée (voir le [guide de l'utilisateur](docs/GUIDE-UTILISATEUR.md)).
- **Aide > À propos de Calc** affiche la version de l'application.
- **Applications pour Windows, macOS (puce Apple) et Linux**, avec Java inclus : rien d'autre à installer (voir le
  [guide d'installation](docs/INSTALLATION.md)). Des sommes SHA-256 permettent de vérifier chaque fichier téléchargé.

### Corrigé

- **Annuler ne perd plus la saisie en cours** : Annuler retire de la ligne ce qui n'est pas encore validé, et Rétablir le
  remet. `Échap` reste un abandon définitif.
- **Une valeur non nulle n'est plus jamais affichée 0.** Quand le signe d'une valeur n'a pas pu être établi, elle s'affiche
  `|x| ≤ b` ; une valeur trop petite pour être encadrée est refusée avec un message clair.
- **Une valeur approchée reste dans son intervalle** : `[1,41728 ; 1,58272]` se lit `≈ 1,5… ± 8,3 × 10⁻²`, et non plus `≈ 2…`.
- **Fichier > Nouveau n'abandonne plus la conversion d'un ancien historique** qui avait échoué : elle reprend au démarrage
  suivant tant que rien n'a été saisi.

### Sécurité

- Les entrées sont bornées avant tout calcul (taille des nombres, profondeur, longueur des lignes, taille des fichiers) ; un
  fichier piégé est refusé avec une explication et ne remplace jamais le vôtre.
- Un fichier écrit par une version plus récente s'ouvre en lecture seule et n'est jamais réécrit.
- Les applications **ne sont pas signées** : Windows SmartScreen et macOS Gatekeeper affichent un avertissement au premier
  lancement. Les sommes SHA-256 prouvent que le fichier est intact, pas qui l'a publié.
