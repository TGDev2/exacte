# Publier et télécharger une version

Les décisions sont dans [`docs/adr/0012-packaging-et-releases.md`](adr/0012-packaging-et-releases.md). Le workflow est
[`.github/workflows/release.yml`](../.github/workflows/release.yml).

## Ce qu'une release contient

Chaque release publie six fichiers :

| Fichier | Contenu |
|---|---|
| `Calc-<version>-windows-x64.zip` | application autonome pour Windows (`Calc\Calc.exe`), runtime Java réduit embarqué |
| `Calc-<version>-macos-arm64.tar.gz` | application autonome pour macOS sur puce Apple (`Calc.app`) |
| `Calc-<version>-linux-x64.tar.gz` | application autonome pour Linux x64 (`Calc/bin/Calc`) |
| `Calc-<version>-sbom.cdx.json` | nomenclature logicielle CycloneDX 1.6 |
| `Calc-<version>-rapport-conformite.md` | rapport de conformité : tests par étiquette, couverture, mutation, garanties et limites (généré par `gradlew conformityReport`, voir [TESTS.md](TESTS.md)) |
| `SHA256SUMS.txt` | somme SHA-256 de chacun des cinq fichiers ci-dessus |

Aucun Java n'est à installer pour lancer l'application : le runtime est dans l'archive. Les modules embarqués sont calculés par
`jdeps` à chaque construction (plus `jdk.localedata` pour le français, voir l'ADR 0012). Pas de Mac Intel ni de Linux arm64.

## Publier une version

Avant de taguer, mettre à jour [`CHANGELOG.md`](../CHANGELOG.md) (déplacer l'entrée « Non publié » sous le numéro de version). Les notes de la release reprennent les messages de commit depuis le tag `vX.Y.Z` précédent.

1. Fusionner dans `main` tout ce qui doit être publié et attendre que la CI (`ci.yml`) y soit verte.
2. Choisir la version `X.Y.Z` avec `X` ≥ 1 (pas de suffixe : `v1.0.0` convient, `v0.9.0` et `v1.0.0-rc1` sont refusés).
3. Créer et pousser le tag :

   ```bash
   git switch main
   git pull
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

4. Le workflow « Release » se lance tout seul (onglet **Actions**) : il valide la version, construit et teste sur les trois systèmes,
   vérifie les modules embarqués et lance chaque application empaquetée (le test contrôle que le processus reste vivant 8 secondes sans erreur Java dans sa sortie ; il ne vérifie pas la fenêtre), puis crée la release avec les cinq fichiers.
5. Ouvrir la release, vérifier la présence des six fichiers, puis suivre la section « Vérifier un téléchargement ».

Le workflow ne vérifie pas que le tag pointe vers un commit de `main` : c'est à la personne qui tague de le faire.

**Si l'exécution échoue après la création de la release (ou à moitié)** : relancer les jobs échoue, car `gh release create` refuse une
release ou un tag qui existe déjà. Supprimer d'abord la release (brouillon compris), puis relancer :
`gh release delete vX.Y.Z --yes` (le tag `vX.Y.Z` reste, c'est voulu : ne pas ajouter `--cleanup-tag`, sauf pour recréer le tag
sur un autre commit).

### Faire un essai sans tag

Onglet **Actions** → **Release** → **Run workflow**, saisir une version de test (par exemple `1.0.0`). Le workflow construit les
mêmes fichiers et crée une **pré-release** nommée `test-vX.Y.Z-<numéro d'exécution>` (aucun tag `vX.Y.Z` n'est créé, donc aucune
exécution en cascade). Après vérification, supprimer la pré-release et son tag :

```bash
gh release delete test-v1.0.0-12 --cleanup-tag --yes
```

### Construire en local (Windows, macOS ou Linux)

```powershell
.\gradlew.bat packageApp -PappVersion=1.0.0     # Windows ; sous Linux et macOS : ./gradlew packageApp -PappVersion=1.0.0
.\package.ps1 -Version 1.0.0                    # Windows : check puis packageApp
```

Le résultat est `build\release\Calc-1.0.0-<windows|macos|linux>-<x64|arm64>.<zip|tar.gz>`. Sans `-PappVersion`, la version vaut
`0.0.0-local` et `packageApp` refuse de continuer (message explicite) : un fichier construit à la main ne doit pas passer pour une
version publiée. Les étapes intermédiaires sont dans `build\packaging\` (`modules.txt` : modules calculés par `jdeps`, `image\` :
l'application avant archivage). Le SBOM : `gradlew cyclonedxBom` → `build\reports\cyclonedx\bom.json`.

## Vérifier un téléchargement

Télécharger l'archive **et** `SHA256SUMS.txt` dans le même dossier, puis comparer.

**Windows (PowerShell)** : afficher la somme, puis la comparer à la ligne du fichier dans `SHA256SUMS.txt` :

```powershell
Get-FileHash .\Calc-1.0.0-windows-x64.zip -Algorithm SHA256
Select-String "windows-x64" .\SHA256SUMS.txt
```

**macOS** (`shasum` est fourni avec le système) : ne vérifier que la ligne du fichier téléchargé :

```bash
grep macos-arm64 SHA256SUMS.txt | shasum -a 256 -c
```

**Linux** :

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

La réponse attendue est `OK` pour chaque fichier téléchargé. Une somme identique prouve que le fichier est bien celui qui a été publié
; elle ne prouve **pas** qui l'a publié (voir ci-dessous).

## Les applications ne sont pas signées

Ni signature de code Windows, ni signature Apple ni notarisation : cela demande des certificats payants et des secrets, hors du
périmètre de cette livraison. Conséquence au premier lancement d'un fichier téléchargé :

- **Windows** : SmartScreen peut afficher « Windows a protégé votre ordinateur ». Cliquer sur **Informations complémentaires**, puis
  sur **Exécuter quand même**. Pour éviter la marque de téléchargement avant de décompresser : clic droit sur le `.zip` →
  Propriétés → cocher **Débloquer**.
- **macOS** : Gatekeeper refuse d'ouvrir `Calc.app` (application d'un développeur non identifié). Décompresser l'archive, puis soit
  ouvrir **Réglages Système → Confidentialité et sécurité** et choisir **Ouvrir quand même** après la première tentative, soit retirer la
  marque de quarantaine dans un terminal :

  ```bash
  xattr -dr com.apple.quarantine Calc.app
  ```

  Ces deux gestes sont indicatifs : le comportement exact varie selon la version de macOS et n'a pas été vérifié sur un Mac pour
  ce projet.
- **Linux** : pas de barrière équivalente. `tar -xzf Calc-<version>-linux-x64.tar.gz` puis `./Calc/bin/Calc` dans une session
  graphique. L'application a besoin des bibliothèques graphiques X11 habituelles d'un bureau (non vérifié sur toutes les
  distributions).

## Limites

- **Pas de signature ni de notarisation** (voir plus haut). Les sommes SHA-256 protègent d'un téléchargement corrompu, pas d'un
  éditeur usurpé.
- **Pas d'installeur natif** (`.msi`, `.exe`, `.dmg`, `.deb`, `.rpm`). Un installeur demande des outils sur la machine de construction
  (WiX pour Windows, par exemple). L'image WiX 3.14 est listée sur l'image de runner Windows Server 2025, mais aucun installeur n'a
  été construit ni vérifié : c'est une suite possible, non une promesse.
- **Reproductibilité : le JAR seulement.** Deux `gradlew clean jar` donnent le même SHA-256, et la CI le contrôle (job `reproducible jar`) sur chaque push vers `main` et sur chaque pull request, sous Linux. Le contrôle ne tourne pas lors d'un push de tag et ne compare pas les JAR des trois systèmes.
  L'image `jpackage` n'est pas garantie identique d'une construction à l'autre : elle contient une copie du JDK installé sur la machine
  (deux releases construites à des semaines d'écart peuvent embarquer deux versions de Java 21), et les archives `.tar.gz` enregistrent
  les dates des fichiers. Deux constructions Windows faites sur le poste de développement sont sorties identiques, ce qui ne prouve rien
  pour les autres systèmes ni pour d'autres jours.
- **Le SBOM ne liste que le classpath d'exécution.** Calc n'a aucune bibliothèque tierce à l'exécution : le SBOM contient donc le
  composant `calc` et aucun autre. Le runtime Java embarqué dans chaque archive n'y figure pas.
- **Architectures** : Windows x64, macOS arm64 (puce Apple), Linux x64. Pas de Mac Intel, pas de Linux arm64.
- **Ce qui n'a pas été exécuté pour E14** : le workflow `release.yml` (GitHub Actions ne se lance pas depuis le poste) et le packaging
  macOS et Linux. Seul Windows a été construit et démarré. La première exécution réelle est l'essai décrit plus haut, à faire après la
  fusion.
