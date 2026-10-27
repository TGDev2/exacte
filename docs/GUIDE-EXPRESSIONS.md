# Guide des expressions

Ce guide décrit ce que l'on peut écrire sur une ligne du futur carnet de calcul. La grammaire complète et les raisons
des choix sont dans [`docs/adr/0007-grammaire-expressions.md`](adr/0007-grammaire-expressions.md). Le langage existe
aujourd'hui sous forme de bibliothèque (`calc.expression`) ; le carnet qui l'utilise arrive avec les Epics suivantes.

## Une ligne

Une ligne contient au choix :

| Ligne | Exemple | Ce qu'elle fait |
|---|---|---|
| vide | | rien |
| commentaire | `# Devis cuisine` | rien, c'est un titre ou une note |
| expression | `(12,5 + 3) × 2 ÷ 4` | calcule une valeur : `7,75` |
| affectation | `taux = 0,2` | calcule une valeur et lui donne un nom |

Une expression ou une affectation peut être suivie d'un commentaire : `prix * (1 + taux)   # prix TTC`. Tout ce qui suit
le premier `#` est ignoré.

## Nombres

- La virgule **ou** le point marquent les décimales : `12,5` et `12.5` sont le même nombre. On peut omettre le zéro :
  `,5` vaut `0,5`.
- **La virgule est toujours décimale.** Aucune fonction ne prend plusieurs arguments, donc une virgule ne sépare jamais
  deux nombres : `1,5` est un nombre, `1,5,3` est une erreur.
- Notation scientifique : `1,5e3` vaut `1500`, `2E-3` vaut `0,002`. L'exposant est compris entre -9999 et 9999.
- Séparateur de milliers : l'**espace insécable** (U+00A0, ou l'espace fine insécable U+202F), par groupes de trois
  chiffres : `1 000 000,50`. Sous Windows, elle se tape avec `Alt` + `0160` sur le pavé numérique ; un copier-coller depuis
  un texte typographié en français la contient souvent.
- **L'espace ordinaire sépare les éléments** : `1 000` avec une espace ordinaire est lu comme deux nombres côte à côte,
  ce qui donne l'erreur « Opérateur manquant » (le message rappelle d'utiliser une espace insécable).
- Pas de fraction littérale : `1/3` est une division, dont le résultat est la fraction exacte 1/3.

Les calculs sont exacts : `(1/3) * 3` vaut exactement `1`, `0,1 + 0,2` vaut exactement `0,3`. Seule une racine carrée qui
ne tombe pas juste donne une valeur approchée, avec une erreur garantie (voir l'ADR 0003).

## Opérateurs et priorités

| Opération | S'écrit | Exemple |
|---|---|---|
| addition | `+` | `2 + 3` |
| soustraction | `-` ou `−` | `5 − 2` |
| multiplication | `*` ou `×` | `6 × 7` |
| division | `/` ou `÷` | `10 ÷ 4` |
| puissance | `^` | `2^10` |
| racine carrée | `√`, `sqrt(...)` ou `racine(...)` | `√2`, `sqrt(2)`, `racine(16)` |
| signe | `-x`, `+x` | `-3` |
| parenthèses | `( )` | `(1 + 2) * 3` |

Du plus fort au plus faible :

1. les parenthèses, `√`, `sqrt( )`, `racine( )` ;
2. la puissance `^`, **associative à droite** : `2^3^2 = 2^(3^2) = 512` ;
3. le signe : `-2^2 = -(2^2) = -4` (comme en mathématiques ; Excel donne 4) ; pour élever un nombre négatif, mettez des
   parenthèses : `(-2)^2 = 4` ;
4. `*` et `/`, de gauche à droite : `2 / 4 / 2 = 0,25` ;
5. `+` et `-`, de gauche à droite : `2 - 3 - 4 = -5`.

Un exposant peut être négatif sans parenthèses : `2^-1 = 1/2`. L'exposant doit être un entier exact entre -4096 et 4096.

`√` s'applique à ce qui le suit immédiatement : `√9^2` vaut `(√9)^2 = 9`, `√(9^2)` vaut `9` aussi, mais `√2 * 3` vaut
`(√2) × 3`. Pour un nombre signé, écrivez `√(-2)` (qui donne d'ailleurs une erreur : pas de racine d'un négatif).

## Mots réservés

`ans`, `sqrt` et `racine` sont réservés, **quelle que soit la casse** (`ANS`, `Sqrt`, `RACINE` fonctionnent aussi). Ils
ne peuvent pas servir de nom de variable.

## Variables

- On crée une variable par une affectation : `taux = 0,2`, puis on l'utilise : `prix * (1 + taux)`.
- Un nom commence par une lettre (accents et autres alphabets compris) ou `_`, puis lettres, chiffres ou `_` :
  `prix`, `taux_TVA`, `année2`, `_x`.
- **Les majuscules comptent** : `Prix` et `prix` sont deux variables différentes.
- Une variable n'existe qu'en dessous de sa définition. Si un nom est affecté plusieurs fois, c'est l'affectation la
  plus proche au-dessus qui compte (`x = x + 1` repart du `x` précédent). Une affectation en erreur rend les lignes qui
  utilisent ce nom en erreur, elle ne laisse jamais reprendre une définition plus ancienne (ADR 0008).

## Références à d'autres lignes

- `ans` : le résultat de la ligne au-dessus la plus proche qui a une valeur (les lignes vides, les commentaires et les
  lignes en erreur sont sautés, comme la touche Ans d'une calculatrice).
- `$3` : le résultat de la ligne 3. Les lignes sont numérotées à partir de 1 (`$0` est une erreur). Une ligne ne peut
  désigner qu'une ligne située au-dessus d'elle ; une ligne qui dépend d'une ligne en erreur est en erreur (« Dépend de
  la ligne 3, qui est en erreur. »).
- Quand des lignes sont insérées, déplacées ou supprimées, les références sont renumérotées automatiquement ; une
  référence vers une ligne supprimée devient `$?` et la ligne affiche « Référence vers une ligne supprimée ».

## Exemples

```text
# Devis cuisine
plan = 1 250,00          # plan de travail (espace insécable)
meubles = 3 480
pose = 35 * 12,5
total = plan + meubles + pose
taux = 0,2
total * (1 + taux)       # TTC
ans / 12                 # mensualité sur un an
√(2) * 3
2^-1
-2^2                     # -4
(-2)^2                   # 4
$5 * 2                   # deux fois la ligne 5
```

## Erreurs fréquentes

Chaque erreur indique **où** se trouve le problème dans la ligne.

| Ligne | Message | À faire |
|---|---|---|
| `2x`, `2(3)`, `(1)(2)` | Opérateur manquant. | Écrire l'opérateur : `2*x`, `2*(3)`. Il n'y a pas de multiplication implicite. |
| `1 000` (espace ordinaire) | Opérateur manquant (pour séparer les milliers, utilisez une espace insécable). | Supprimer l'espace ou utiliser une espace insécable. |
| `2 ×` | Opérande manquant après « × ». | Compléter le calcul. |
| `(2 + 3` | Parenthèse fermante manquante. | Fermer la parenthèse signalée. |
| `2 + 3)` | Parenthèse fermante « ) » sans parenthèse ouvrante. | Retirer la parenthèse ou ajouter l'ouvrante. |
| `2 @ 3` | Caractère inattendu « @ ». | Retirer le caractère. |
| `1,5,3` | Nombre invalide « 1,5,3 ». | Une seule virgule décimale par nombre. |
| `sqrt 4` | Parenthèse ouvrante attendue après « sqrt ». | Écrire `sqrt(4)` ou `√4`. |
| `ans = 3` | « ans » est un mot réservé : choisissez un autre nom de variable. | Choisir un autre nom. |
| `2 = 3` | Le signe « = » ne peut suivre qu'un nom de variable en début de ligne. | Une affectation s'écrit `nom = expression`. |
| `1 / 0` | Division par zéro : le diviseur vaut 0. | |
| `√(-4)` | La racine carrée d'un nombre négatif n'est pas définie en réel. | |
| `2^0,5` | L'exposant doit être un entier. | |
| `prix * 2` | Variable inconnue « prix ». | Définir `prix` sur une ligne précédente. |
| `$9` | La ligne 9 n'existe pas. | |
| `$?` | Référence vers une ligne supprimée. | Remplacer la référence. |

Limites d'une ligne : 1000 caractères, 100 niveaux d'imbrication (parenthèses, signes, racines, exposants), 500 éléments
(nombres, noms, opérations). Au-delà, la ligne est refusée avec un message, sans calcul.

## Ce qui n'est pas pris en charge

- **La multiplication implicite** (`2x`, `2(3)`, `(1)(2)`) : écrivez `*` ou `×`.
- **Le pourcentage** (`20 %`) : écrivez `* 0,2` ou `* 20 / 100`.
- **Les constantes** `π` et `e` : `pi` et `e` sont aujourd'hui des noms de variable ordinaires.
- Les fonctions à plusieurs arguments, les unités, les nombres complexes.

Ces extensions sont possibles plus tard ; elles demanderont une nouvelle décision (ADR) parce qu'elles changent le sens
de lignes existantes.
