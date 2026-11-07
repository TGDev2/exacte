# Format du fichier d'historique (version 2) et du carnet (version 3)

Ce document décrit le fichier `history.json` écrit par Calc (version 2), puis, dans la dernière partie, le carnet `.calc.json` (version 3) et les exports. Les décisions et leurs raisons sont dans
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

Le carnet de lignes d'expressions (version 3) est décrit plus bas : c'est un autre `format` (`calc-notebook`), qui ne remplace pas ce fichier.

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


---

# Format du carnet (version 3)

Le carnet (`calc.notebook`, ADR 0008) est un document ordonné de lignes. Le fichier `.calc.json` en est la sauvegarde : **seuls les textes
sources sont écrits, jamais un résultat**. Les résultats sont recalculés à chaque chargement (politique « le source est la vérité »).
Les décisions sont dans `docs/adr/0009-documents-et-exports.md`. Les fichiers de la version 2 (`history.json`) ne changent pas.

## Où sont les fichiers

Dans le dossier de données du tableau plus haut :

| Fichier | Rôle |
|---|---|
| `carnet-courant.calc.json` | le carnet sans titre, enregistré automatiquement après chaque commande validée |
| `history.json`, `history.tsv`, `history.lock` | l'historique (versions 2 et 1) et le verrou, **partagé** avec le carnet : une seconde instance ne charge le carnet qu'en lecture seule |
| `history.json.v2.bak` (`.v2.bak.1`...) | copie de l'historique faite avant la conversion en carnet, jamais écrasée |
| `carnet-courant.calc.json.AAAAMMJJ-HHMMSS.bak` | copie d'un carnet courant abîmé, faite avant de le remplacer |

Un document que l'utilisateur a enregistré lui-même (nouveau, ouvrir, enregistrer sous) est un fichier de son choix, d'extension **`.calc.json`**
(ajoutée quand le nom ne se termine pas déjà par elle, sans tenir compte de la casse). Il n'est **jamais** enregistré automatiquement.

## Structure

Exemple complet (écrit par l'application ; le mode d'affichage est celui qui était choisi) :

```json
{
  "format": "calc-notebook",
  "version": 3,
  "displayMode": "auto",
  "lines": [
    { "id": "1", "source": "# Devis cuisine" },
    { "id": "2", "source": "surface = 12,5" },
    { "id": "3", "source": "surface * 42" }
  ]
}
```

| Champ | Type | Sens |
|---|---|---|
| `format` | texte | toujours `"calc-notebook"` ; un fichier `"calc-history"` n'est pas un carnet et est refusé (« Ce fichier n'est pas un carnet Calc. ») |
| `version` | entier | `3` pour ce document, jamais inférieur à 3 |
| `displayMode` | texte, facultatif | `auto`, `decimal`, `fraction` ou `scientific` ; absent ou inconnu : `auto` |
| `lines` | tableau | les lignes, dans l'ordre. Le numéro d'une ligne (celui des références `$n`) est sa position, de 1 à 5000 |
| `lines[].id` | texte | l'identifiant stable de la ligne (E10) : de 1 à 18 chiffres, sans zéro initial. Facultatif |
| `lines[].source` | texte | le texte de la ligne tel que l'utilisateur l'a écrit (obligatoire, sans saut de ligne) |

Les champs inconnus, à tous les niveaux, sont **ignorés** (et ne sont pas conservés à la réécriture). Aucun résultat, valeur, erreur ni numéro de ligne
n'est écrit : un fichier modifié à la main reste cohérent.

## Lecture

1. **La structure décide de tout, le texte de rien.** Une ligne dont le texte est invalide pour le langage (`1 +`, `$99`, un nom inconnu, une ligne de plus de
   1000 caractères) est **conservée telle quelle** et apparaît en erreur dans le carnet. Aucune ligne n'est supprimée, réparée ou déplacée. Seule une structure
   qui n'est pas celle d'un carnet empêche le chargement (pas du JSON, pas de l'UTF-8, pas de `format` ni de `version` valides, `lines` absent, un élément
   de `lines` qui n'est pas un objet avec un `source` texte), avec un message clair.
2. **Identifiants.** Le premier porteur d'un identifiant le garde ; une ligne dont l'identifiant est absent, mal formé (nombre JSON, zéro initial,
   plus de 18 chiffres) ou en double reçoit, dans l'ordre du fichier, le numéro qui suit le plus grand identifiant valide. C'est déterministe (deux lectures
   du même fichier donnent les mêmes identifiants) et le nombre de lignes concernées est journalisé. Les lignes que l'utilisateur ajoutera ensuite
   continuent après le plus grand identifiant.
3. **Sauts de ligne.** Un `source` qui en contient (impossible dans le modèle, possible dans un fichier étranger) voit chacun remplacé par une espace : la ligne
   n'est pas coupée en deux, donc les numéros `$n` du fichier gardent leur sens. Le fait est journalisé.
4. **Recalcul.** Le chargeur rend le carnet ; l'éditeur (`NotebookEditor`) le recalcule en entier au premier affichage. Le chargeur n'évalue rien lui-même.

### Limites

| Limite | Valeur |
|---|---|
| Taille du fichier | 16 Mio (vérifiée avant la lecture, qui est elle-même bornée à cette taille plus un octet) |
| Nombre de lignes | 5000 (`Notebook.MAX_LINES`), vérifié sur le tableau avant la lecture d'une ligne |
| Longueur d'une ligne (`source`) | 16 384 caractères (le langage en accepte 1000 et signale une ligne plus longue en erreur ; le fichier en accepte plus pour la conserver) |
| Nombre de valeurs JSON | 100 000 (objets, tableaux, chaînes, nombres) : refuse un fichier de millions de valeurs minuscules avant qu'il remplisse la mémoire |
| Profondeur, chaîne, littéral numérique | 32 niveaux, 16 384 caractères, 32 caractères (inchangé) |

Un fichier qui dépasse une limite est refusé en quelques millisecondes (mesures dans `docs/TESTS.md`). Le carnet courant hors limites **n'est jamais remplacé**.
À l'écriture, l'application n'écrit jamais un fichier qu'elle refuserait de relire et ne raccourcit jamais un carnet : une ligne de plus de 16 384 caractères, ou un carnet
de plus de 16 Mio, n'est **pas écrit** et l'erreur est signalée (l'enregistrement automatique la signale par `autosaveProblem()`).

### Versions

| `version` | Comportement |
|---|---|
| 3 | lu et écrit |
| supérieure à 3 | ouvert en **lecture seule** quand les lignes se lisent avec les règles de la version 3 (« Carnet créé par une version plus récente de Calc : ouvert en lecture seule. »), sinon rien n'est affiché ; **jamais réécrit**, ni par l'enregistrement automatique ni par « Enregistrer » ; « Enregistrer sous » un autre nom reste possible ; la règle vaut aussi pour la **cible** d'un « Enregistrer sous » : un fichier existant d'une version plus récente que 3 est refusé (même message), avant toute écriture et avant la question « Remplacer ? », qu'il soit ou non le carnet ouvert ; une cible illisible, qui n'est pas un carnet ou qui est de la version 3 ou d'une version plus ancienne garde la confirmation habituelle |
| absente, inférieure à 3, non entière | fichier refusé |

## Écriture

Toujours atomique (fichier temporaire, `force`, déplacement atomique, comme la version 2) : un échec laisse l'ancien fichier intact. Un carnet courant abîmé est copié
(`.AAAAMMJJ-HHMMSS.bak`) avant d'être remplacé ; si la copie échoue, l'écriture est refusée.

## Migration de l'historique vers le carnet

Au démarrage, dans le dossier de données, sous le verrou :

| Situation | Résultat |
|---|---|
| `carnet-courant.calc.json` existe | il est lu ; l'historique n'est pas touché (c'est ce qui rend la migration idempotente) |
| il n'existe pas et `history.json` existe | copie `history.json.v2.bak`, conversion de chaque entrée en ligne par le pont d'E10 (`1,5 + 2,5`, `2 ^ (-3)`, `(1/3) * 3`, `√(9)`), dans l'ordre, puis écriture du carnet ; « Votre historique a été converti en carnet (n lignes). » |
| il n'existe ni l'un ni l'autre, mais `history.tsv` (version 1) | la chaîne complète : conversion en `history.json` (`history.tsv.v1.bak`), puis comme ci-dessus |
| une entrée qu'aucune ligne ne peut écrire (opérande approché, opérande plus long qu'une ligne) | une ligne de commentaire `# Historique non converti : ...` qui dit ce qu'était l'entrée |
| plus de 5000 entrées | les 5000 plus récentes sont converties ; la phrase d'état dit où sont les autres (la copie) |
| une copie ou une écriture échoue, ou l'historique est illisible ou d'une version plus récente | rien n'est créé ni modifié ; le carnet démarre vide ; nouvel essai au démarrage suivant, tant qu'aucun carnet courant n'a été écrit. Pendant cette attente, un carnet vide n'est jamais écrit (Fichier > Nouveau, changement de mode, Enregistrer sous d'un carnet sans titre) ; le premier carnet avec des lignes écrit y met fin, même si ces lignes sont effacées ensuite, et une écriture qui échoue n'y met pas fin |
| seconde instance (verrou pris) | conversion en mémoire seulement : ni copie, ni écriture |

`history.json` n'est ni renommé ni supprimé : l'interface actuelle le lit encore (jusqu'à E13) et la copie est le filet de sécurité.

Résultat sur les 11 fichiers de référence de `src/test/resources/legacy/v1` (test `NotebookMigrationTest`, chaîne v1 → v2 → v3) :

| Fichier | Lignes du carnet | Valeurs exactes recalculées |
|---|---|---|
| `nominal.tsv` | 6 : `0,1 + 0,2`, `10 - 2,5`, `1,5 * 4`, `7 / 2`, `2 ^ 10`, `√(16)` | `3/10`, `15/2`, `6`, `7/2`, `1024`, `4` |
| `rounded.tsv` | 1 : `1 / 3` | `1/3` |
| `legacy-float.tsv` | 1 : `0,1 + 0,2` | `3/10` |
| `crlf.tsv` | 3 : `1 + 2`, `2 * 4`, `√(9)` | `3`, `8`, `3` |
| `bom.tsv` | 2 : `1 + 2`, `5 - 2` | `3`, `3` |
| `bom-header.tsv` | 1 : `1 + 2` | `3` |
| `empty.tsv` | 0 | - |
| `one-corrupted-line.tsv` | 2 (une ligne rejetée dès la version 2) : `1 + 2`, `3 * 4` | `3`, `12` |
| `inconsistent.tsv` | 0 (une ligne rejetée dès la version 2) | - |
| `wrong-result.tsv` | 0 (une ligne rejetée dès la version 2) | - |
| `future-version.tsv` | non lu, non converti, intact | - |

## Exports

Le résultat de chaque ligne est écrit dans le **mode d'affichage passé en paramètre** (auto, décimal, fraction, scientifique). Les lignes vides sont omises ; les
autres gardent leur numéro dans le carnet, de sorte qu'un `$n` désigne toujours la ligne numérotée `n`. L'export JSON est le fichier du carnet lui-même.

**Markdown** (`NotebookExporter.markdown`) : le titre, puis le tableau `| n° | Expression | Résultat |`. Dans chaque cellule, les caractères que Markdown ou un
moteur de rendu pourrait lire (`\ ` * _ [ ] < > | ~ & $`) sont précédés d'une barre oblique inverse (`$` parce que les lignes contiennent des `$n` et que
certains moteurs lisent `$...$` comme des maths) et un saut de ligne devient `<br>`. Une valeur approchée porte « ≈ » et la borne d'erreur
(`≈ 1,41421356237… (± 2,1 × 10⁻²⁵)`), une ligne en erreur son message (`Erreur : Opérande manquant après « + ».`), un commentaire son texte sans le `#`.

**CSV** (`NotebookExporter.csv`) : séparateur `;`, UTF-8 **avec marque d'ordre des octets**, fins de ligne **CRLF** (ce que l'Excel français ouvre correctement), champs entre
guillemets quand ils contiennent `;`, `"`, CR ou LF, guillemets doublés (RFC 4180). Colonnes :

| Colonne | Contenu |
|---|---|
| `n°` | le numéro de la ligne |
| `Expression` | le texte de la ligne, tel qu'écrit (commentaire compris) |
| `Résultat affiché` | la valeur dans le mode choisi, avec « ≈ » et la borne d'erreur pour une valeur approchée ; le message pour une ligne en erreur ; vide pour un commentaire |
| `Valeur exacte` | le texte canonique de la valeur, **seulement** pour un résultat exact (`1/3`, `12,5`) ; vide sinon |
| `Statut` | `exact`, `approché`, `erreur` ; vide pour un commentaire |

**Neutralisation de l'injection de formules.** Un tableur exécute comme formule une cellule qui commence par `=`, `+`, `-`, `@`, une tabulation ou un retour chariot ; un
carnet reçu d'un tiers peut en contenir (`# =HYPERLINK(...)`). **Toute cellule qui commence par l'un de ces six caractères est préfixée d'une apostrophe**, avant la
mise entre guillemets (`=1+2` devient `'=1+2`). Effet visible, sans exception : l'apostrophe fait partie de la cellule quand le fichier est lu comme du texte, et un nombre négatif
(`-3`, dans le résultat affiché comme dans la valeur exacte) est préfixé lui aussi, donc lu comme du texte et non comme un nombre. La colonne `Valeur exacte` est faite pour être lue
par un programme (qui retire l'apostrophe), pas additionnée dans un tableur.
