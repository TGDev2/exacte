# Installer Calc

Ce guide s'adresse à toute personne qui veut utiliser Calc, même sans connaître l'informatique. Il n'y a **rien à installer** :
Calc est livré dans une archive (un dossier compressé) qui contient déjà tout ce qu'il faut, y compris Java. Il suffit de
télécharger l'archive de son système, de la décompresser et de lancer l'application.

Pour chaque système, les étapes sont les mêmes :

1. choisir l'archive sur la page des versions ;
2. vérifier le fichier téléchargé (facultatif, mais recommandé) ;
3. décompresser l'archive et lancer Calc ;
4. passer l'avertissement du premier lancement.

**Où télécharger.** Ouvrez la page des versions : <https://github.com/TGDev2/exacte/releases>. Prenez la version la plus
haute de la liste, par exemple `1.0.0`. **Ne prenez pas** les pré-versions dont le nom commence par `test-` (par exemple
`test-v1.0.0-12`) : ce sont des essais du mainteneur, pas des versions pour les utilisateurs.

Sur la page d'une version, descendez jusqu'à la liste **Assets** (les fichiers). Vous y trouverez une archive par système et un
fichier `SHA256SUMS.txt`. Dans les noms ci-dessous, `1.0.0` est le numéro de version : s'il a changé, remplacez-le par celui
que vous voyez.

**Les applications ne sont pas signées.** Le mainteneur n'a pas acheté les certificats que Windows et Apple demandent pour
reconnaître un éditeur. Au premier lancement, votre système affichera donc un avertissement : c'est attendu, et le guide
explique comment le passer. Cet avertissement existe pour une bonne raison : n'installez que ce que vous avez téléchargé sur la
page ci-dessus.

## Windows

Cette archive est faite pour Windows 64 bits (x64).

**1. Choisir l'archive.** Sur la page des versions, téléchargez `Calc-1.0.0-windows-x64.zip`. Téléchargez aussi `SHA256SUMS.txt`
si vous voulez vérifier le fichier (étape 2), dans le même dossier (par exemple **Téléchargements**).

**2. Vérifier la somme SHA-256 (facultatif).** Une somme SHA-256 est une longue suite de lettres et de chiffres qui sert
d'empreinte à un fichier. Si votre copie a la même empreinte que celle publiée, elle n'a pas été abîmée ni modifiée pendant le
téléchargement. Pour la calculer :

1. ouvrez le dossier **Téléchargements** dans l'Explorateur de fichiers ;
2. cliquez dans la barre d'adresse, tapez `powershell` et appuyez sur Entrée : une fenêtre de commandes s'ouvre dans ce dossier ;
3. copiez cette ligne, collez-la dans la fenêtre et appuyez sur Entrée :

```powershell
(Get-FileHash .\Calc-1.0.0-windows-x64.zip -Algorithm SHA256).Hash
```

4. comparez le résultat à la ligne `windows-x64.zip` du fichier `SHA256SUMS.txt` (ouvrez-le avec le Bloc-notes). Les majuscules
   et les minuscules ne comptent pas : seules les lettres et les chiffres doivent être identiques.

Si les deux suites sont différentes, ne lancez pas l'application et téléchargez de nouveau l'archive.

**3. Décompresser et lancer.** Ne lancez pas Calc depuis l'archive : il faut d'abord tout extraire.

1. faites un clic droit sur `Calc-1.0.0-windows-x64.zip` et choisissez **Extraire tout…** ;
2. choisissez un dossier de votre choix (par exemple `Documents`) et validez ;
3. ouvrez le dossier `Calc` obtenu, puis double-cliquez sur `Calc.exe`.

Ce dossier `Calc` est l'application : vous pouvez le déplacer où vous voulez, par exemple sur le Bureau.

**4. Passer l'avertissement.** Au premier lancement, Windows peut afficher le message
« Windows a protégé votre ordinateur » (c'est le filtre SmartScreen). Pour continuer :

1. cliquez sur **Informations complémentaires** ;
2. cliquez sur le bouton **Exécuter quand même**.

## macOS

Calc fonctionne uniquement sur les Mac à **puce Apple** (la famille M1, M2 et suivantes). **Les Mac à processeur Intel ne sont pas
pris en charge** : cette archive n'est pas faite pour eux. Pour savoir lequel vous avez, ouvrez le menu Pomme, puis **À propos de ce
Mac** : le nom de la puce ou du processeur y est indiqué (le mot « Intel » désigne un Mac non pris en charge).

> **Non vérifié.** Ces étapes n'ont pas encore été essayées sur un vrai Mac. Elles décrivent ce qui devrait se passer d'après
> le fonctionnement habituel de macOS. Si quelque chose est différent chez vous, c'est utile de le signaler (voir la dernière
> section).

**1. Choisir l'archive.** Sur la page des versions, téléchargez `Calc-1.0.0-macos-arm64.tar.gz`. Téléchargez aussi
`SHA256SUMS.txt` si vous voulez vérifier le fichier.

**2. Vérifier la somme SHA-256 (facultatif).** Une somme SHA-256 est l'empreinte d'un fichier : si la vôtre est identique à celle
publiée, le fichier n'a pas été abîmé pendant le téléchargement. Ouvrez l'application **Terminal** (dans
**Applications → Utilitaires**), puis tapez `cd ` (avec une espace après), glissez le dossier **Téléchargements** dans la
fenêtre et appuyez sur Entrée. Ensuite :

```bash
shasum -a 256 Calc-1.0.0-macos-arm64.tar.gz
```

Comparez la suite obtenue à la ligne `macos-arm64.tar.gz` de `SHA256SUMS.txt`. Elles doivent être identiques. Sinon, ne lancez
pas l'application et téléchargez de nouveau l'archive.

**3. Décompresser et lancer.** Un double-clic sur `Calc-1.0.0-macos-arm64.tar.gz` devrait créer `Calc.app`. Si rien ne se passe,
dans le Terminal, au même endroit :

```bash
tar -xzf Calc-1.0.0-macos-arm64.tar.gz
```

Glissez ensuite `Calc.app` dans le dossier **Applications** (ou laissez-la où elle est), puis ouvrez-la.

**4. Passer l'avertissement.** Calc n'est pas signée : macOS devrait refuser de l'ouvrir la première fois, en disant qu'elle vient
d'un développeur non identifié. Pour l'autoriser :

1. essayez d'ouvrir `Calc.app` une fois, puis fermez le message ;
2. ouvrez **Réglages Système**, puis **Confidentialité et sécurité** ;
3. descendez jusqu'à la zone **Sécurité** : un message mentionne Calc ;
4. cliquez sur **Ouvrir quand même**, puis confirmez.

Si macOS dit que « l'application est endommagée » et propose de la mettre à la corbeille, l'archive n'est pas forcément
abîmée : c'est l'**hypothèse** la plus probable, à confirmer sur un vrai Mac. macOS marque les fichiers téléchargés d'une
« quarantaine » qu'il refuse parfois de lever pour une application non signée. Dans ce cas, dans le Terminal, au même endroit
que `Calc.app` :

```bash
xattr -dr com.apple.quarantine Calc.app
```

Cette commande retire la marque de quarantaine de `Calc.app` ; ouvrez ensuite l'application de nouveau. Les noms exacts des menus
varient selon la version de macOS.

## Linux

Calc fonctionne sur Linux **x64** (processeur Intel ou AMD 64 bits) uniquement, dans une session graphique.

> **Non vérifié.** Ces étapes n'ont pas encore été essayées sur une vraie machine Linux. L'application a besoin des
> bibliothèques graphiques habituelles d'un bureau ; on ne sait pas encore sur quelles distributions elle démarre.

**1. Choisir l'archive.** Sur la page des versions, téléchargez `Calc-1.0.0-linux-x64.tar.gz`. Téléchargez aussi
`SHA256SUMS.txt` si vous voulez vérifier le fichier.

**2. Vérifier la somme SHA-256 (facultatif).** Ouvrez un terminal dans le dossier où se trouvent les fichiers, puis :

```bash
shasum -a 256 Calc-1.0.0-linux-x64.tar.gz
```

Si la commande `shasum` n'existe pas chez vous, utilisez `sha256sum` à la place :

```bash
sha256sum Calc-1.0.0-linux-x64.tar.gz
```

Comparez la suite obtenue à la ligne `linux-x64.tar.gz` de `SHA256SUMS.txt`. Elles doivent être identiques. Sinon, ne lancez pas
l'application et téléchargez de nouveau l'archive.

**3. Décompresser et lancer.** Dans le même dossier :

```bash
tar -xzf Calc-1.0.0-linux-x64.tar.gz
./Calc/bin/Calc
```

La première commande crée le dossier `Calc` ; la seconde lance l'application, qui est le fichier `Calc/bin/Calc`.

**4. Avertissement.** Linux n'affiche pas d'avertissement équivalent à ceux de Windows et de macOS. Si votre gestionnaire de
fichiers refuse d'exécuter `Calc/bin/Calc`, lancez-le depuis un terminal comme ci-dessus.

## Où sont vos données

Calc enregistre votre carnet tout seul, dans un dossier **séparé** de l'application :

| Système | Dossier |
|---|---|
| Windows | `%APPDATA%\Calc` (si `APPDATA` n'existe pas : `<votre profil>\AppData\Roaming\Calc`) |
| macOS | `~/Library/Application Support/Calc` |
| Linux | `$XDG_DATA_HOME/calc` si la variable `XDG_DATA_HOME` est définie, sinon `~/.local/share/calc` |

Le signe `~` désigne votre dossier personnel. Sous Windows, tapez `%APPDATA%\Calc` dans la barre d'adresse de l'Explorateur pour
y aller. Sous macOS, dans le Finder, utilisez le menu **Aller → Aller au dossier…** et collez le chemin.

Le carnet courant est le fichier `carnet-courant.calc.json`. Le dossier peut aussi contenir `history.json` (l'ancien
historique) et des copies de sécurité qui se terminent par `.bak`. Les documents que vous enregistrez avec **Enregistrer sous**
sont à l'endroit que vous avez choisi. Le mode d'affichage (Auto, Décimal, Fraction, Scientifique) est retenu à part, dans les
préférences Java de votre système, pas dans ce dossier.

## Mettre à jour sans perdre votre carnet

Vos données sont **hors du dossier de l'application** (voir la section précédente). Pour passer à une nouvelle version, il suffit
donc de remplacer le dossier de l'application par celui de la nouvelle version :

1. téléchargez l'archive de la nouvelle version, comme dans la section de votre système ;
2. fermez Calc ;
3. supprimez l'ancien dossier `Calc` (ou l'ancienne `Calc.app` sous macOS) et mettez le nouveau à la place ;
4. relancez Calc : votre carnet est toujours là.

**Conseil :** avant de remplacer quoi que ce soit, copiez le dossier de données (voir plus haut) à un autre endroit, par exemple sur
le Bureau. C'est une précaution qui ne coûte rien.

Si un carnet a été écrit par une version **plus récente** de Calc que celle que vous lancez, Calc l'ouvre en lecture seule et ne
le réécrit jamais : vous ne risquez pas de l'abîmer. Pour le modifier, prenez la version la plus récente.

## Désinstaller

Il n'y a pas d'installeur, donc rien à désinstaller au sens habituel :

1. fermez Calc ;
2. supprimez le dossier de l'application : le dossier `Calc` obtenu en décompressant l'archive, ou `Calc.app` sous macOS ;
3. si vous le souhaitez, supprimez aussi le dossier des données (voir « Où sont vos données »). **Attention :** votre carnet
   en fait partie, il sera perdu. Gardez-le si vous pensez réinstaller Calc un jour.

Vous pouvez aussi supprimer l'archive téléchargée.

## Si ça ne marche pas

- **L'application ne se lance pas depuis Windows.** Vérifiez que vous avez bien extrait l'archive (**Extraire tout…**) et que
  vous ne lancez pas `Calc.exe` depuis l'intérieur du fichier `.zip`.
- **La somme SHA-256 est différente.** Le fichier est incomplet ou abîmé : supprimez-le et téléchargez-le de nouveau. Si la
  différence persiste, ne lancez pas l'application et signalez-le (voir plus bas). Rappel : cette somme vérifie le fichier, pas
  son éditeur.
- **Vous avez un Mac Intel.** Cette version ne le prend pas en charge, et il n'y a pas d'autre archive pour ce type de Mac.
- **Votre système est trop ancien.** Les versions minimales de Windows, de macOS et de Linux n'ont pas encore été établies. Si
  Calc ne démarre pas sur un système ancien, mettez-le à jour si possible, puis réessayez.
- **Un avertissement s'affiche au premier lancement.** C'est normal, les applications ne sont pas signées : suivez l'étape 4 de
  votre système.
- **Vous avez téléchargé une pré-version `test-`.** Retournez sur la page des versions et prenez la version la plus haute qui ne
  commence pas par `test-`.

**Signaler un problème.** Ouvrez une demande sur <https://github.com/TGDev2/exacte/issues> : indiquez votre système et sa
version, le nom de l'archive téléchargée et ce que vous avez vu à l'écran. Les étapes pour macOS et Linux n'ont pas été essayées
sur de vraies machines : vos retours sont précieux.

Pour utiliser Calc une fois lancé, voir le [guide de l'utilisateur](GUIDE-UTILISATEUR.md). La procédure de publication, réservée
au mainteneur, est dans [RELEASE.md](RELEASE.md).
