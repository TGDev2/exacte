# Guide de l'utilisateur

Calc est un carnet de calcul : on écrit une expression par ligne, le résultat s'affiche à droite. Ce guide explique comment
s'en servir. La syntaxe détaillée des expressions est dans [GUIDE-EXPRESSIONS.md](GUIDE-EXPRESSIONS.md) (elle n'est pas
répétée ici) ; elle est aussi résumée dans le menu **Aide > Syntaxe des expressions**.

## Le carnet

- Chaque ligne est un champ. `Entrée` valide la ligne et passe à la suivante ; après la dernière, une nouvelle ligne est
  créée.
- Une ligne est une expression (`prix * 3`), une affectation (`prix = 12,5`), un commentaire (`# Devis`) ou rien.
- Modifier une ligne recalcule celles qui en dépendent. Insérer, supprimer ou déplacer des lignes renumérote les références
  `$n` : `$3` suit sa ligne ; vers une ligne supprimée, elle devient une erreur explicite.
- `ans` est le dernier résultat au-dessus de la ligne ; `$3` est le résultat de la ligne 3.
- Un carnet vide montre trois exemples en filigrane (`prix = 12,5`, `prix * 3`, `ans / 2`) ; ils ne sont pas enregistrés.
  Sous ce filigrane, trois boutons proposent de partir d'un exemple : **Devis**, **Racine de 2 approchée** et
  **Références $n**. Un clic (ou `Tab` puis `Espace`) insère les lignes de l'exemple, en une seule action : la première
  ligne devient la ligne active, et un seul **Annuler** rend le carnet vide. Dès qu'une ligne est écrite, les boutons
  disparaissent ; ils reviennent si le carnet redevient vide (après un **Annuler**, par exemple).
- **Aide > Syntaxe des expressions** ouvre une fenêtre qui ne bloque pas le carnet : le résumé de la syntaxe, puis des
  exemples ayant chacun un bouton **Insérer**. Il ajoute les lignes de l'exemple sous la ligne courante, en une seule
  action que **Annuler** retire d'un coup, et la fenêtre reste ouverte pour en insérer d'autres.
- Annuler et rétablir portent sur les lignes **validées**. `Échap` abandonne définitivement la saisie en cours dans la ligne.
  Annuler la retire d'abord, et Rétablir la remet, sans la valider, tant que rien d'autre n'a changé : une nouvelle saisie, une
  commande validée ou annulée, une autre ligne ou un autre document la font oublier.
- Coller (`Ctrl+V`, ou glisser-déposer) un texte de plusieurs lignes dans une ligne en fait autant de lignes, à cet endroit :
  la première suit le texte placé avant le curseur, la dernière précède le texte qui le suit, et les autres lignes ne bougent
  pas. Les fins de ligne de Windows, macOS et Linux sont reconnues ; un saut de ligne final n'ajoute pas de ligne vide. Les
  lignes collées sont validées et calculées, et un seul `Ctrl+Z` retire tout le collage (`Ctrl+Y` le refait). Un collage qui
  ferait dépasser 5000 lignes au carnet ou 16 384 caractères à une ligne est refusé en entier : le carnet ne change pas et la
  barre d'état l'explique par un message qui commence par « Collage refusé ». Un texte sans saut de ligne s'insère dans le
  champ, comme une frappe, sans rien valider.
- Pendant la frappe, la zone du résultat montre un **aperçu** en gris, sans signe dans la marge : le résultat qu'aurait la
  ligne avec le texte tapé, calculé en arrière-plan avec les lignes du dessus (variables, `$n`, `ans`). Rien n'est validé ni
  enregistré, et les autres lignes ne changent pas : `Entrée` valide la ligne et son résultat remplace l'aperçu, `Échap` rend
  le texte validé et efface l'aperçu. Un texte incomplet ou en erreur (`2 +`) n'a pas d'aperçu ; son erreur n'apparaît qu'une
  fois la ligne validée. Pendant le calcul du carnet, l'aperçu attend la frappe suivante.
- Le calcul se fait en arrière-plan : la fenêtre répond toujours, même sur un très gros carnet. Une ligne pas encore calculée
  affiche « Calcul en cours… » et la barre d'état compte « Calcul en cours : n / N lignes — Échap pour interrompre ». Toute nouvelle commande remplace le
  calcul en cours. Les lignes d'un très gros carnet apparaissent par tranches, en 2 à 3 secondes pour 5000 lignes.
- Sans saisie en cours, `Échap` interrompt le calcul : les lignes restantes disent « Non calculée : calcul interrompu », et la
  commande suivante (valider, ajouter, supprimer, annuler, rétablir, changer de mode, ouvrir, Nouveau, exporter) relance le
  calcul. Se déplacer d'une ligne à l'autre ne le relance pas.

## Lire un résultat

Les calculs sont exacts : `1 / 3` vaut exactement un tiers, `0,1 + 0,2` vaut exactement `0,3`, et `(1 / 3) * 3` redonne
exactement `1`. Seule une racine carrée qui ne tombe pas juste est approchée.

| Ce que vous voyez | Ce que cela veut dire |
|---|---|
| signe **=** dans la marge | la valeur est exacte (une fraction). Le texte peut être arrondi pour l'affichage, la valeur gardée ne l'est pas |
| signe **≈** dans la marge | la valeur est une approximation (racine non exacte) ; la vraie valeur est certainement dans l'intervalle calculé |
| signe **!** dans la marge | la ligne est en erreur, voir le message sous la ligne |
| nom à gauche du résultat (`total`) | la ligne définit cette variable (`total = plan + meubles + pose`) |
| ligne qui commence par `#` | un titre de section, en gras ; son texte reste tel quel dans la ligne |
| **≈** devant un nombre | même chose : seuls les chiffres garantis sont affichés (`√2 ≈ 1,41421356237…`) |
| **\|x\| ≤ …** | le signe de la valeur n'a pas pu être établi : elle vaut peut-être 0, et elle est au plus … en valeur absolue (`√2 - √2` donne `\|x\| ≤ 4,2 × 10⁻²⁵`) |
| **…** après les chiffres | des chiffres existent mais ne sont pas affichés (`1 / 3 = 0,333333333333…`) |
| info-bulle (survol du résultat) | « Erreur maximale : ± … » pour une valeur approchée ; « Valeur exacte : 1/3 (affichage arrondi) » quand le texte est arrondi |
| `m × 10ⁿ` | notation scientifique, utilisée en dehors de `[10⁻⁶, 10¹⁵[` en mode Auto |
| message sous la ligne, partie soulignée | la ligne est en erreur ; le message dit pourquoi et le soulignement montre où |

Un nombre non nul n'est jamais affiché `0` : une valeur trop petite pour être encadrée sans toucher 0 est refusée, avec le
même message que `(1/16) ^ 4096`. La couleur seule ne porte jamais l'information : le signe de la marge est un caractère,
et un lecteur d'écran entend « valeur exacte » ou « valeur approchée » dans la description de la ligne. Les résultats sont
alignés sur la virgule : les unités de toutes les lignes tombent dans la même colonne.

## Le détail de la ligne active

Sous la ligne où se trouve le curseur, le carnet explique comment sa valeur a été obtenue et à quoi elle sert. Pour la ligne
`total = plan + meubles + pose` du devis de cuisine :

```text
= 1250 + 3480 + 437,5
= 5167,5
Sources : lignes 2, 3 et 4
Utilisée par 1 ligne
```

- **Le développement** (`= 1250 + 3480 + 437,5`) : la ligne où chaque variable, `$n` ou `ans` de valeur exacte est remplacé
  par sa valeur, sur un seul niveau. Il n'apparaît que si la ligne utilise d'autres lignes. Une valeur approchée garde son
  écriture.
- **Le résultat**, tel que la ligne l'affiche dans le mode en cours : précédé de « = » s'il est exact ; une valeur approchée
  commence déjà par « ≈ » (ou se lit `|x| ≤ …`).
- **Les sources** : les lignes dont elle se sert. Elles sont aussi marquées **◆** dans la marge, et un lecteur d'écran
  entend « source de la ligne active » dans leur description.
- **Les utilisations** : « Utilisée par 1 ligne », « Utilisée par 3 lignes » ou « Utilisée par aucune ligne ».
- Pour une valeur approchée, **« Valeur garantie entre … et … »** : les bornes de l'intervalle calculé, arrondies vers
  l'extérieur à 12 chiffres significatifs ; la vraie valeur est certainement entre les deux (`√2` : entre 1,41421356237 et
  1,41421356238).

Un titre, un commentaire, une ligne vide ou une ligne en erreur n'ont pas de détail. Le détail suit les modifications : il
apparaît dès que le calcul en arrière-plan est terminé.

## La barre d'état

La barre sous le carnet dit, de gauche à droite :

- le message du moment : l'aide de la saisie, la confirmation ou le refus de la dernière commande, et pendant un calcul
  « Calcul en cours : n / N lignes — Échap pour interrompre » ;
- l'état d'enregistrement : « Enregistrement automatique » pour le carnet sans titre, « Enregistré » pour un document sans
  modification, « Modifié » après une modification pas encore enregistrée, « Lecture seule » pour un document écrit par une
  version plus récente de Calc ;
- le nombre de lignes du carnet, sans la ligne vide du bas : « 0 ligne », « 1 ligne », « 8 lignes » ;
- le résumé des résultats, une fois le calcul fini : « 7 exactes, 0 approchée, 0 en erreur ». Les commentaires et les lignes vides
  ne sont pas comptés. Pendant un calcul, le résumé précédent reste affiché ; après une interruption, il est vide.

Chaque texte se lit en toutes lettres : la couleur ne fait que l'accompagner.

## Modes d'affichage et thème

Le menu **Affichage**, et le contrôle segmenté en haut de la fenêtre (quatre boutons côte à côte, celui du mode en vigueur
enfoncé), changent la notation des résultats, **sans rien recalculer** : Auto, Décimal, Fraction, Scientifique. `Ctrl+M`
passe au mode suivant (toujours `Ctrl`, même sous macOS où `Cmd+M` minimise la fenêtre) ; le bouton enfoncé et le menu
suivent. Au clavier, `Tab` atteint chaque bouton et `Espace` le choisit. Le mode est enregistré avec le carnet et mémorisé
entre deux lancements ; si les préférences de Java sont inaccessibles, l'application démarre en mode Auto.

**Affichage > Thème** choisit l'apparence de la fenêtre : **Clair** ou **Sombre**, et **Système** sous Windows, qui suit le
réglage clair ou sombre de Windows (lu au lancement de Calc). Sous macOS et Linux, Système n'est pas proposé. Le changement
s'applique tout de suite à la fenêtre ouverte, sans rien recalculer ni perdre ce qui est en cours de saisie. Le thème est
mémorisé entre deux lancements ; sans choix mémorisé, ou si les préférences de Java sont inaccessibles, Calc démarre en
thème Clair.

Le titre de la fenêtre nomme le document : « Carnet sans titre — Calc », « Devis.calc.json — Calc », avec « (modifié) »
quand des changements ne sont pas enregistrés et « (lecture seule) » pour un fichier d'une version plus récente.

Les définitions précises (nombre de chiffres, bascule en notation scientifique, fraction secondaire) sont dans
[l'ADR 0004](adr/0004-regles-affichage.md).

## Documents

Le carnet sans titre est **enregistré automatiquement** après chaque ligne validée. Pour garder plusieurs carnets, utilisez le
menu **Fichier** (le modificateur de menu est `Ctrl` sous Windows et Linux, `Cmd` sous macOS) :

| Entrée | Touche | Effet |
|---|---|---|
| Nouveau | `Ctrl+N` | carnet vide sans titre ; demande d'abord si des modifications seraient perdues |
| Ouvrir… | `Ctrl+O` | ouvre un fichier `.calc.json` ; si l'ouverture échoue, le carnet courant reste tel quel |
| Enregistrer | `Ctrl+S` | écrit le fichier du document (un carnet sans titre demande un nom) |
| Enregistrer sous… | `Ctrl+Maj+S` | demande un nom (`.calc.json` ajouté), confirme avant de remplacer un fichier |
| Exporter en Markdown… / en CSV… | | écrit le carnet dans le mode d'affichage courant ; confirme avant de remplacer un fichier |
| Quitter | | comme le bouton de fermeture |

- Le titre de la fenêtre est « Carnet sans titre — Calc » ou « devis.calc.json — Calc » ; un point `•` devant le nom signale
  des modifications non enregistrées (document avec fichier).
- Nouveau, Ouvrir et Quitter proposent « Enregistrer », « Ne pas enregistrer » ou « Annuler » quand un document avec fichier
  est modifié.
- Un fichier **d'une version plus récente** de Calc s'ouvre en lecture seule (Enregistrer est désactivé, Enregistrer sous
  reste possible). Un fichier illisible, invalide ou de plus de 16 Mio est refusé avec la raison dans la barre d'état.
  Les lignes en erreur d'un fichier ouvert sont conservées et comptées.
- Le fichier contient les **textes** des lignes et le mode d'affichage, jamais les résultats : ils sont recalculés à
  l'ouverture. Le format est décrit dans [FORMAT-FICHIER.md](FORMAT-FICHIER.md).

## Exports

- **Markdown** : le titre du document, puis un tableau `n° | Expression | Résultat` (le résultat est le texte affiché dans le
  mode courant ; une erreur s'écrit « Erreur : … »).
- Pendant un calcul, l'export attend sa fin sans bloquer la fenêtre, puis écrit le carnet tel qu'il est alors. `Échap`, Ouvrir ou
  Nouveau l'abandonnent (la barre d'état le dit), et fermer la fenêtre demande d'abord confirmation. Enregistrer n'attend rien : le
  fichier ne contient pas les résultats.
- **CSV** : séparateur `;`, UTF-8 avec marque d'ordre des octets (s'ouvre dans Excel), une ligne par ligne non vide, cinq
  colonnes `n°;Expression;Résultat affiché;Valeur exacte;Statut`. Une cellule qui commencerait par `=`, `+`, `-` ou `@` est
  préfixée d'une apostrophe pour qu'un tableur ne la prenne pas pour une formule. Cette protection est partielle : voir les limites connues du [README](../README.md#limites-connues).

## Raccourcis

Le modificateur de menu est `Ctrl` sous Windows et Linux, `Cmd` sous macOS. La liste est aussi dans **Aide > Raccourcis clavier**.

| Touche | Action |
|---|---|
| `Entrée` | valider la ligne et passer à la suivante |
| `↑` / `↓` | ligne précédente / suivante (la ligne quittée est validée) |
| `Échap` | abandonner la saisie en cours dans la ligne ; sans saisie, interrompre le calcul, aussi depuis les boutons des modes |
| `Ctrl+Z` | retirer la saisie en cours ; sans saisie, annuler la dernière modification validée |
| `Ctrl+Y`, `Ctrl+Maj+Z` | remettre la saisie retirée par Annuler ; sinon, rétablir la dernière modification annulée |
| `Ctrl+Entrée` / `Ctrl+Maj+Entrée` | insérer une ligne au-dessous / au-dessus |
| `Ctrl+Maj+K` | supprimer la ligne |
| `Ctrl+Maj+↑` / `Ctrl+Maj+↓` | monter / descendre la ligne |
| `Ctrl+M` | mode d'affichage suivant (toujours `Ctrl`) |
| `Ctrl+N`, `Ctrl+O`, `Ctrl+S`, `Ctrl+Maj+S` | Nouveau, Ouvrir, Enregistrer, Enregistrer sous |
| `Ctrl+K` | ouvrir la palette de commandes |

Sous macOS, ces touches sont vérifiées par leur liaison dans la fenêtre avec `Cmd` simulé ; elles n'ont pas été essayées sur
un clavier Mac réel.

## Palette de commandes

`Ctrl+K` (`Cmd+K` sous macOS), ou **Aide > Palette de commandes…**, ouvre la palette dans la fenêtre : un champ « Rechercher
une commande » et la liste de toutes les commandes des menus Fichier, Édition, Affichage et Aide, dans l'ordre des menus,
chacune avec son raccourci à droite. Tapez une partie du nom : la recherche ne tient compte ni des majuscules ni des accents
(« decimal » trouve « Mode Décimal », « enreg » trouve Enregistrer et Enregistrer sous…). Sans résultat, la palette affiche
« Aucune commande ».

Les commandes portent le texte de leur menu, sauf les modes d'affichage (« Mode Auto », « Mode Décimal », « Mode Fraction »,
« Mode Scientifique ») et les thèmes (« Thème : Clair », « Thème : Sombre », « Thème : Système » sous Windows). `↑` et `↓`
choisissent une commande, `Entrée` l'exécute exactement comme son menu, ferme la palette et rend le focus à la ligne. Une
commande indisponible dans les menus (Annuler sans rien à annuler) est grisée et `Entrée` ne fait rien. `Échap` ferme la
palette sans rien exécuter : la saisie en cours dans la ligne est conservée et un calcul en cours continue.

## Tout au clavier

Tout se fait sans souris. Par exemple, pour le devis du README : tapez chaque ligne et validez-la par `Entrée`, remontez à
la ligne `total` par `↑` (son détail s'affiche dessous : sources, « Utilisée par 1 ligne »), ouvrez la palette par `Ctrl+K`,
tapez « sombre » et `Entrée` pour passer au thème sombre, puis `Tab` jusqu'au contrôle segmenté des modes et `Espace` sur
**Fraction** ; `Tab` revient aux lignes, et `Ctrl+Z` annule la dernière modification. Chaque élément qui prend le focus
(ligne, bouton de mode, bouton d'exemple, recherche de la palette) a un nom lu par les lecteurs d'écran : une ligne
s'annonce « Ligne 5 », par exemple.

## Version

Le numéro de version de l'application empaquetée se lit dans **Aide > À propos de Calc** ; une exécution depuis les sources
affiche « Version de développement ».

## Où sont mes données

Le dossier de données dépend du système :

| Système | Dossier |
|---|---|
| Windows | `%APPDATA%\Calc` (à défaut : `<dossier personnel>\AppData\Roaming\Calc`) |
| macOS | `~/Library/Application Support/Calc` |
| Linux | `$XDG_DATA_HOME/calc` si la variable est un chemin absolu, sinon `~/.local/share/calc` |

Il contient `carnet-courant.calc.json` (le carnet sans titre), `history.lock` (le verrou, vide) et, selon les cas, les copies de
sécurité ci-dessous. Le mode d'affichage est mémorisé à part, dans les préférences de Java (`java.util.prefs`).
Les documents que vous enregistrez avec **Enregistrer sous** sont ailleurs : à l'endroit que vous avez choisi.

## Sauvegardes et migrations

- **Premier lancement après une ancienne version** : l'ancien historique (`history.tsv`, puis `history.json`) est converti en
  carnet, une ligne par calcul, et la barre d'état le dit. Avant de remplacer quoi que ce soit, Calc garde une copie :
  `history.tsv.v1.bak` et `history.json.v2.bak`. Une copie existante n'est jamais écrasée, et relancer la conversion ne
  change aucun fichier.
- **Fichier du carnet abîmé** : il est d'abord copié sous `carnet-courant.calc.json.AAAAMMJJ-HHMMSS.bak`, puis remplacé par un
  carnet vide ; la barre d'état donne le nom de la copie. Si la copie est impossible, Calc n'écrit plus rien plutôt que de
  perdre le fichier.
- **Écriture** : chaque enregistrement écrit un fichier temporaire puis le substitue d'un coup ; si l'écriture échoue,
  l'ancien fichier reste intact et l'échec est signalé dans la barre d'état (la modification reste dans la fenêtre).

## Si quelque chose ne va pas

| Situation | Que faire |
|---|---|
| Windows (SmartScreen) ou macOS (Gatekeeper) refuse d'ouvrir l'application | elle n'est pas signée : suivre [RELEASE.md](RELEASE.md) pour l'ouvrir malgré l'avertissement, après avoir vérifié la somme SHA-256 |
| « Calc est déjà ouvert » dans la barre d'état, la fenêtre n'enregistre rien | une autre fenêtre de Calc a le verrou du dossier de données ; la fermer, puis relancer. Entre-temps, **Enregistrer sous** permet de garder le travail dans un fichier |
| « version plus récente » | le fichier vient d'une version plus récente de Calc : il est ouvert en lecture seule et n'est jamais modifié ; mettre Calc à jour |
| un échec d'enregistrement s'affiche | vérifier l'espace disque et les droits du dossier de données ; la modification est conservée et réessayée au coup suivant |
| une ligne est en erreur | lire le message sous la ligne : il dit pourquoi et où ; le tableau des erreurs fréquentes est dans [GUIDE-EXPRESSIONS.md](GUIDE-EXPRESSIONS.md) |
| un fichier `.calc.json` est refusé | le message en barre d'état donne la raison (format, taille, version) ; le carnet courant n'a pas été touché |

Limites à connaître : une ligne fait au plus 16 384 caractères dans un fichier (l'éditeur refuse davantage) et au plus 1000
caractères à évaluer ; un carnet compte au plus 5000 lignes ; coller du texte sur plusieurs lignes crée autant de
lignes, et un collage qui dépasserait ces limites est refusé en entier. La liste complète des limites est dans le [README](../README.md#limites-connues).
