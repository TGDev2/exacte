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

## Verification

Le projet fournit des tests automatiques couvrant :

- calcul binaire valide avec virgule et point
- calcul unaire valide
- erreurs de saisie ciblees
- division par zero
- formatage et reutilisation du resultat
- reutilisation d'un resultat d'historique
- comportement du workflow apres un calcul reussi
- restauration de l'historique apres relance
- degradation non bloquante si l'historique persistant est invalide
- verification Swing des raccourcis, du changement unaire/binaire et de la reutilisation depuis l'UI

Commande :

```powershell
powershell -ExecutionPolicy Bypass -File .\check.ps1
```

## Packaging recommande pour une evaluation

Commande :

```powershell
powershell -ExecutionPolicy Bypass -File .\package.ps1
```

Le script :

- verifie le projet
- cree une application Windows portable dans `dist\Calc\Calc.exe`
- cree une archive de distribution dans `dist\Calc-portable.zip`

Ce chemin est le plus adapte a une demonstration portfolio : l'evaluateur peut ouvrir directement l'executable package sans IDE ni script de compilation.

## Lancer l'application depuis les sources

Commande :

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

Le script compile le projet puis lance l'application, sans dependre d'un IDE.

## Historique persistant

L'historique est stocke localement sur le poste utilisateur :

- sous Windows : `%APPDATA%\Calc\history.tsv`
- en repli : `~\.calc\history.tsv`

Si ce fichier est absent ou invalide, l'application reste utilisable et le statut explique ce qui a ete restaure ou non.

## Pourquoi ce projet va au-dela d'un exercice basique

- Le moteur de calcul est deja isole de l'interface.
- La logique d'interaction n'est plus cachee dans la fenetre Swing.
- Les validations utilisateur sont formulees comme des messages exploitables.
- L'historique sert le workflow et survit desormais a la relance.
- Les parcours UI visibles sont verifies automatiquement, pas seulement la logique metier.
- Le depot permet a un evaluateur de verifier puis distribuer rapidement une version executable.

## Limites connues

- L'application reste un outil desktop local, avec une persistance d'historique limitee au poste utilisateur.
- Le perimetre mathematique reste volontairement restreint aux operations de base et a deux usages unaires/binaires simples.
- Les tests UI couvrent les parcours critiques, pas un rendu graphique pixel par pixel.
- Le packaging produit une application portable Windows, pas encore un installeur signe ou une release automatisee.
