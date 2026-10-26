# Format du fichier d'historique (version 2)

Ce document décrit le fichier `history.json` écrit par Calc. Les décisions et leurs raisons sont dans
`docs/adr/0005-format-persistance.md`.

## Où est le fichier

| Système | Dossier de données |
|---|---|
| Windows | `%APPDATA%\Calc` (à défaut : `<dossier personnel>\AppData\Roaming\Calc`) |
| macOS | `~/Library/Application Support/Calc` |
| Linux et autres | `$XDG_DATA_HOME/calc` si la variable est un chemin absolu, sinon `~/.local/share/calc` |

Le dossier contient `history.json` (les données), `history.lock` (le verrou, vide), et au besoin les copies décrites plus bas.

## Structure

Exemple complet, écrit par l'application pour `1 ÷ 3`, `0,1 + 0,2`, `√2`, puis `(√2)²` calculé à partir du résultat réutilisé de la racine (le dernier opérande est donc un intervalle). La racine est une valeur approchée : ses deux bornes sont des fractions exactes dont le dénominateur est une puissance de 2 ; les décimaux saisis sont réécrits en fractions exactes (`0,1` est `1/10`).

```json
{
  "format": "calc-history",
  "version": 2,
  "entries": [
    { "op": "division", "operands": ["1", "3"], "result": { "exact": true, "value": "1/3" } },
    { "op": "addition", "operands": ["1/10", "1/5"], "result": { "exact": true, "value": "3/10" } },
    { "op": "sqrt", "operands": ["2"], "result": { "exact": false, "lower": "1709679290002018430137083/1208925819614629174706176", "upper": "3419358580004036860274167/2417851639229258349412352" } },
    { "op": "power", "operands": [{ "exact": false, "lower": "1709679290002018430137083/1208925819614629174706176", "upper": "3419358580004036860274167/2417851639229258349412352" }, "2"], "result": { "exact": false, "lower": "664613997892457936451903471216237059/332306998946228968225951765070086144", "upper": "10633823966279326983230461759236816895/5316911983139663491615228241121378304" } }
  ]
}
```

### Champs

| Champ | Type | Sens |
|---|---|---|
| `format` | texte | toujours `"calc-history"` ; tout autre fichier est refusé |
| `version` | entier | `2` pour ce document ; le seul nombre JSON du fichier |
| `entries` | tableau | les calculs, du plus ancien au plus récent |
| `entries[].op` | texte | identifiant stable de l'opération (table ci-dessous) |
| `entries[].operands` | tableau | un opérande pour `sqrt`, deux pour les autres opérations |
| `entries[].result` | objet | la valeur calculée |

**Identifiants d'opération** : `addition`, `subtraction`, `multiplication`, `division`, `power`, `sqrt`. Ils sont en minuscules,
ne dépendent pas des noms du code Java, ne changent jamais et ne sont jamais réutilisés.

**Valeur exacte** : le texte canonique du noyau numérique, `p` pour un entier, `p/q` sinon (fraction réduite, `q > 0`, signe devant
`p`) : `"3"`, `"-7"`, `"1/3"`, `"-22/7"`. **Aucun nombre JSON** n'est utilisé pour une valeur, donc aucun flottant ; les décimaux (`0,3`),
les exposants (`1e3`) et les fractions non réduites (`2/4`) sont refusés à la lecture.

**Résultat** : `{ "exact": true, "value": t }` pour une valeur exacte, `{ "exact": false, "lower": t, "upper": t }` pour une valeur
approchée dont la vraie valeur est certainement dans `[lower, upper]` (`lower < upper`). Un intervalle réduit à un point s'écrit
comme une valeur exacte.

**Opérande** : le texte `t` d'une valeur exacte, ou le même objet qu'un résultat quand l'opérande est une valeur approchée
(un résultat de racine réutilisé).

## Lecture

Le chargeur ne fait confiance à rien. Chaque entrée est **recalculée** par le moteur à partir de ses opérandes, puis comparée au
résultat enregistré ; une entrée mal formée, d'opération inconnue, dont le moteur refuse les opérandes, ou dont le résultat diffère
du recalcul est **écartée et comptée**, jamais affichée. Le chargeur renvoie les entrées, les rejets (position, cause) et la copie
de sauvegarde éventuelle. Les champs inconnus, à tous les niveaux, sont ignorés (ils ne sont pas conservés à la réécriture).

### Limites

| Limite | Valeur |
|---|---|
| Taille du fichier | 8 Mio (vérifiée avant la lecture) |
| Nombre d'entrées | 10 000 |
| Longueur d'une chaîne | 16 384 caractères |
| Profondeur d'imbrication | 32 |
| Longueur d'un littéral numérique JSON | 32 caractères |

Un fichier qui dépasse une limite est refusé tout de suite (quelques millisecondes), sans copie, et **n'est jamais remplacé**.
Quand l'historique de l'application dépasse 10 000 entrées ou 8 Mio, l'écriture laisse de côté les plus anciennes : l'application
n'écrit jamais un fichier qu'elle refuserait de relire.

### Versions

| `version` | Comportement |
|---|---|
| 2 | lu et écrit |
| supérieure à 2 | ouvert en **lecture seule** quand c'est possible (« Historique créé par une version plus récente de Calc : ouvert en lecture seule. »), sinon pas du tout ; **jamais réécrit**, jamais copié |
| absente, inférieure à 2, non entière | fichier abîmé : copié puis remplaçable |

La version 3 (carnet de lignes d'expressions) étendra ce format : elle ajoutera des champs et changera `version`.

## Écriture

Toujours atomique (fichier temporaire, `force`, déplacement atomique) : un échec laisse l'ancien fichier intact. Si le dernier
chargement a rejeté des entrées, une copie horodatée `history.json.AAAAMMJJ-HHMMSS.bak` est faite avant tout (suffixe `-1`, `-2`... si
le nom existe ; jamais écrasée).

## Verrou

`history.lock` porte un verrou exclusif du système (`FileChannel.tryLock`) pris à l'ouverture et rendu à la fermeture ou à la fin du
processus, même après un plantage. Une deuxième fenêtre charge l'historique mais ne l'écrit pas : « Calc est déjà ouvert : l'historique de
cette fenêtre ne sera pas enregistré. ».

## Migration depuis la version 1 (`history.tsv`)

| Situation au démarrage | Résultat |
|---|---|
| seul `history.tsv` existe | lecture tolérante, écriture de `history.json`, puis renommage en `history.tsv.v1.bak` (`.v1.bak.1`... si le nom existe) ; « Historique converti au nouveau format (n entrées). » |
| les deux existent | `history.json` fait foi, `history.tsv` n'est pas touché |
| `history.tsv` d'une version plus récente que 1 | ni lu, ni converti, ni renommé |
| macOS/Linux, ancien `~/.calc/history.tsv`, dossier de données vide | copié dans le dossier de données, l'ancien est renommé `history.tsv.v1.bak`, puis conversion |
| échec de la conversion | `history.tsv` intact ; nouvel essai au démarrage suivant |

Résultat sur les 11 fichiers de référence de `src/test/resources/legacy/v1` (test `LegacyMigrationTest`) :

| Fichier | Entrées | Rejets | Valeurs exactes après migration |
|---|---|---|---|
| `nominal.tsv` | 6 | 0 | `3/10`, `15/2`, `6`, `7/2`, `1024`, `4` |
| `rounded.tsv` | 1 | 0 | `1/3` (le fichier v1 contenait `0,333333333333`) |
| `legacy-float.tsv` | 1 | 0 | `3/10` (le fichier v1 contenait `0.30000000000000004`) |
| `crlf.tsv` | 3 | 0 | `3`, `8`, `3` |
| `bom.tsv` | 2 | 0 | `3`, `3` |
| `bom-header.tsv` | 1 | 0 | `3` |
| `empty.tsv` | 0 | 0 | document vide |
| `one-corrupted-line.tsv` | 2 | 1 | `3`, `12` |
| `inconsistent.tsv` | 0 | 1 | - |
| `wrong-result.tsv` | 0 | 1 | - |
| `future-version.tsv` | 0 | - | non migré, intact, lecture seule |
