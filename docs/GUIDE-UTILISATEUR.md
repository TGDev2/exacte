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
- Annuler et rétablir portent sur les lignes **validées**. `Échap` abandonne la saisie en cours dans la ligne.
- Le calcul se fait en arrière-plan : la fenêtre répond toujours, même sur un très gros carnet. Une ligne pas encore calculée
  affiche « Calcul en cours… » et la barre d'état compte « Calcul en cours : n / N lignes ». Toute nouvelle commande remplace le
  calcul en cours. Les lignes d'un très gros carnet apparaissent par tranches, en une seconde environ.
- Sans saisie en cours, `Échap` interrompt le calcul : les lignes restantes disent « Non calculée : calcul interrompu », et la
  commande suivante (valider, ajouter, supprimer, annuler, rétablir, changer de mode, ouvrir, Nouveau, exporter) relance le
  calcul. Se déplacer d'une ligne à l'autre ne le relance pas.

## Lire un résultat

Les calculs sont exacts : `1 / 3` vaut exactement un tiers, `0,1 + 0,2` vaut exactement `0,3`, et `(1 / 3) * 3` redonne
exactement `1`. Seule une racine carrée qui ne tombe pas juste est approchée.

| Ce que vous voyez | Ce que cela veut dire |
|---|---|
| badge **Exact** | la valeur est exacte (une fraction). Le texte peut être arrondi pour l'affichage, la valeur gardée ne l'est pas |
| badge **≈ Approché** | la valeur est une approximation (racine non exacte) ; la vraie valeur est certainement dans l'intervalle calculé |
| **≈** devant un nombre | même chose : seuls les chiffres garantis sont affichés (`√2 ≈ 1,41421356237…`) |
| **…** après les chiffres | des chiffres existent mais ne sont pas affichés (`1 / 3 = 0,333333333333…`) |
| info-bulle (survol du résultat) | « Erreur maximale : ± … » pour une valeur approchée ; « Valeur exacte : 1/3 (affichage arrondi) » quand le texte est arrondi |
| `m × 10ⁿ` | notation scientifique, utilisée en dehors de `[10⁻⁶, 10¹⁵[` en mode Auto |
| message sous la ligne, partie soulignée | la ligne est en erreur ; le message dit pourquoi et le soulignement montre où |

Un nombre non nul n'est jamais affiché `0`. Le badge est toujours accompagné d'un texte : la couleur seule ne porte pas
l'information.

## Modes d'affichage

Le menu **Affichage** (et le sélecteur en haut de la fenêtre) change la notation des résultats, **sans rien recalculer** :
Auto, Décimal, Fraction, Scientifique. `Ctrl+M` passe au mode suivant (toujours `Ctrl`, même sous macOS où `Cmd+M`
minimise la fenêtre). Le mode est enregistré avec le carnet et mémorisé entre deux lancements ; si les préférences de Java
sont inaccessibles, l'application démarre en mode Auto.

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
  préfixée d'une apostrophe pour qu'un tableur ne la prenne pas pour une formule.

## Raccourcis

Le modificateur de menu est `Ctrl` sous Windows et Linux, `Cmd` sous macOS. La liste est aussi dans **Aide > Raccourcis clavier**.

| Touche | Action |
|---|---|
| `Entrée` | valider la ligne et passer à la suivante |
| `↑` / `↓` | ligne précédente / suivante (la ligne quittée est validée) |
| `Échap` | abandonner la saisie en cours dans la ligne ; sans saisie, interrompre le calcul, aussi depuis le sélecteur de modes |
| `Ctrl+Z` | annuler la dernière modification validée |
| `Ctrl+Y`, `Ctrl+Maj+Z` | rétablir |
| `Ctrl+Entrée` / `Ctrl+Maj+Entrée` | insérer une ligne au-dessous / au-dessus |
| `Ctrl+Maj+K` | supprimer la ligne |
| `Ctrl+Maj+↑` / `Ctrl+Maj+↓` | monter / descendre la ligne |
| `Ctrl+M` | mode d'affichage suivant (toujours `Ctrl`) |
| `Ctrl+N`, `Ctrl+O`, `Ctrl+S`, `Ctrl+Maj+S` | Nouveau, Ouvrir, Enregistrer, Enregistrer sous |

Sous macOS, ces touches sont vérifiées par leur liaison dans la fenêtre avec `Cmd` simulé ; elles n'ont pas été essayées sur
un clavier Mac réel.

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
caractères à évaluer ; un carnet compte au plus 5000 lignes ; coller du texte sur plusieurs lignes dans un champ joint les
lignes en une seule. La liste complète des limites est dans le [README](../README.md#limites-connues).
