# Calc

Calc est une calculatrice desktop Java/Swing en francais concue comme un projet portfolio. Elle met l'accent sur la fiabilite de la saisie, la lisibilite du comportement et une separation claire entre moteur de calcul, logique d'interaction et interface graphique.

## Proposition de valeur

Ce projet ne cherche pas a impressionner par un catalogue d'operations. Il cherche a montrer qu'un outil simple peut etre :

- robuste sur les cas courants et les erreurs utilisateur
- fluide a utiliser au clavier
- structure de maniere lisible et testable
- presentable rapidement a un evaluateur technique

## Ce que l'application fait

- Operations prises en charge : addition, soustraction, multiplication, division, puissance, racine carree
- Saisie francaise souple : virgule ou point acceptes
- Messages d'erreur explicites et cibles
- Historique reutilisable
- Historique persistant entre les sessions
- Enchainement rapide des calculs : apres un succes, le resultat revient dans le premier champ pour preparer le calcul suivant
- Politique de precision explicite : le resultat affiche est celui qui sera reutilise et persiste
- Raccourcis utiles :
  - `Entree` : calculer
  - `Echap` : effacer les champs
  - `Ctrl+R` : reprendre le dernier resultat

## Architecture

Le projet est volontairement compact, mais les responsabilites sont separees :

- `Calcul/CalculatorEngine.java` : validation et execution des calculs
- `Graphique/CalculatorPresenter.java` : logique d'interaction, gestion d'historique, statut, focus et preparation du workflow suivant
- `Graphique/CalculatorFrame.java` : rendu Swing et branchement des actions utilisateur
- `Calcul/FileCalculationHistoryStore.java` : persistance locale de l'historique hors de l'interface graphique

Cette separation permet de tester les parcours d'usage critiques sans dependre d'une verification manuelle de l'UI.

## Prerequis

- JDK 17 minimum (le projet est compile avec `--release 17`).
- Maven n'est pas a installer : le Maven Wrapper est fourni (`mvnw.cmd` sous Windows, `./mvnw` sous Linux et macOS).
- `jpackage` (inclus dans le JDK) n'est necessaire que pour le packaging Windows.

Commandes principales :

```powershell
.\mvnw.cmd -B verify                 # compile, execute les tests, produit target\calc.jar
.\mvnw.cmd -B verify "-Dcalc.ui.required=true"   # echoue si les tests d'interface ne peuvent pas s'executer (CI)
java -jar target\calc.jar            # lance l'application apres verify
```

Sans affichage graphique, les tests d'interface sont ignores (signales comme `skipped`), sauf si `calc.ui.required=true` est positionne.

## Verification

Le projet fournit des tests automatiques couvrant :

- calcul binaire valide avec virgule et point
- calcul unaire valide
- erreurs de saisie ciblees
- division par zero
- formatage et reutilisation du resultat
- cas decimaux sensibles (`0,1 + 0,2`, `1 / 3`, chainage apres arrondi)
- reutilisation d'un resultat d'historique
- comportement du workflow apres un calcul reussi
- restauration de l'historique apres relance
- normalisation d'un historique numerique provenant d'une ancienne precision flottante
- degradation non bloquante si l'historique persistant est invalide
- verification Swing des raccourcis, du changement unaire/binaire et de la reutilisation depuis l'UI

Commande :

```powershell
.\mvnw.cmd -B verify
```

Le script `check.ps1` est une enveloppe de cette commande.

## Packaging recommande pour une evaluation

Commande :

```powershell
powershell -ExecutionPolicy Bypass -File .\package.ps1
```

Le script :

- verifie le projet (`mvnw.cmd -B verify`)
- cree une application Windows portable dans `dist\Calc\Calc.exe`
- cree une archive de distribution dans `dist\Calc-portable.zip`

Ce chemin est le plus adapte a une demonstration portfolio : l'evaluateur peut ouvrir directement l'executable package sans IDE ni script de compilation.

## Lancer l'application depuis les sources

Commande :

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

Le script construit le projet sans les tests (`target\calc.jar`) puis lance l'application, sans dependre d'un IDE.

## Historique persistant

L'historique est stocke localement sur le poste utilisateur :

- sous Windows : `%APPDATA%\Calc\history.tsv`
- en repli : `~\.calc\history.tsv`

Si ce fichier est absent ou invalide, l'application reste utilisable et le statut explique ce qui a ete restaure ou non.

## Precision numerique

La calculatrice applique une regle unique de bout en bout :

- un resultat reussi est formate, reutilise pour le calcul suivant et persiste avec la meme valeur
- additions, soustractions et multiplications restent exactes sur les decimaux saisis
- divisions non terminantes et racines carrees non exactes sont arrondies a 12 decimales avec `HALF_UP`
- les puissances acceptent uniquement un exposant entier compris entre `-999` et `999`
- la saisie attend un decimal simple avec virgule ou point ; la notation scientifique n'est pas prise en charge

Cette politique evite qu'un nombre soit affiche d'une facon, reutilise avec une autre valeur, puis restaure differemment apres relance.

## Pourquoi ce projet va au-dela d'un exercice basique

- Le moteur de calcul est deja isole de l'interface.
- La logique d'interaction n'est plus cachee dans la fenetre Swing.
- Les validations utilisateur sont formulees comme des messages exploitables.
- L'historique sert le workflow et survit desormais a la relance.
- Les parcours UI visibles sont verifies automatiquement, pas seulement la logique metier.
- Le depot permet a un evaluateur de verifier puis distribuer rapidement une version executable.

## Limites connues

- L'application reste un outil desktop local, avec une persistance d'historique limitee au poste utilisateur.
- Le perimetre mathematique reste volontairement restreint aux operations de base, a la racine carree et aux puissances a exposant entier.
- Les tests UI couvrent les parcours critiques, pas un rendu graphique pixel par pixel.
- Le packaging produit une application portable Windows, pas encore un installeur signe ou une release automatisee.
