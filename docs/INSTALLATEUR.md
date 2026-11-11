# Installateur Windows OLPOS (OLPOS-Setup.exe)

## Fabriquer l'installateur

Outils (une seule fois) :

- JDK 17 (fournit `jpackage`) — ex. Eclipse Temurin 17
- Inno Setup 6 : `winget install JRSoftware.InnoSetup --scope user`

Puis, à la racine du dépôt :

```
build-installer.bat            (version 3.81.1 par défaut)
build-installer.bat 3.81.2     (numéro de version choisi)
```

Résultat : `dist\OLPOS-Setup.exe` (≈ 53 Mo). Étapes du script :

1. `build.bat` compile `unicentaopos.jar` ;
2. `jpackage --type app-image` crée `dist\OLPOS` avec Java inclus
   (option `-Dolpos.installed=true`) ;
3. Inno Setup compile `installer\olpos.iss`.

## Ce que fait l'installateur

- Assistant en français : Suivant → Installer → Terminer.
- Installation par utilisateur, **sans droits administrateur** :
  programme dans `%LOCALAPPDATA%\Programs\OLPOS`.
- Raccourcis Bureau et menu Démarrer (+ « OLPOS - Licences »).
- Entrée « OLPOS » dans Paramètres > Applications, avec désinstallation.
- Données dans `%LOCALAPPDATA%\OLPOS` (dossier séparé, accessible en écriture) :
  `olpos.properties`, `database\` (Derby), `derby.log`.
  Ni les mises à jour ni la désinstallation ne touchent ce dossier.
- La version installée n'utilise jamais `~\unicentaopos.properties` ni
  `~\unicentaopos-database` : une installation uniCenta existante sur le même
  PC n'est pas modifiée.
- Aucune base, configuration de test ou mot de passe n'est inclus. La base est
  créée au premier lancement (comptes d'exemple uniCenta sans mot de passe :
  à définir dans Maintenance > Utilisateurs).

## Prise de commande sur téléphone

Activée d'office dans la version installée (`AppConfig` : `api.enabled=true`,
`api.port=8095`, `machine.ticketsbag=restaurant` quand `-Dolpos.installed=true`).
L'assistant affiche la case « Activer la prise de commande sur téléphone »
(cochée ; une mise à jour reprend le choix précédent). Décochée, l'installateur
écrit `api.enabled=false` dans `%LOCALAPPDATA%\OLPOS\olpos.properties` sans
toucher aux autres réglages ; recochée, il remet `api.enabled=true`.

Une fois par PC : mot de passe du serveur (Maintenance > Utilisateurs) et
pare-feu Windows (fenêtre « Autoriser l'accès » au premier lancement, Wi-Fi en
réseau privé). Détails dans `LISEZMOI.txt` installé avec OLPOS.

## Licences

OLPOS est dérivé d'uniCenta oPOS / Openbravo POS (GNU GPL v3). L'installateur
copie `LICENSE.md` (`licences\LICENCE-GPL-3.0.txt`), les licences des
bibliothèques (`licences\bibliotheques\`) ; celles de Java sont dans
`runtime\legal\`.

## Mise à jour

Lancer simplement le nouvel `OLPOS-Setup.exe` : l'ancien programme est remplacé
(`[InstallDelete]` de `app\` et `runtime\`), les données restent. Si une mise à
jour est annulée pendant la copie des fichiers, relancer l'installateur pour
réparer le programme (les données ne sont pas concernées).

## Limites connues

- L'exe n'est pas signé numériquement : Windows SmartScreen peut afficher
  « Windows a protégé votre ordinateur » → « Informations complémentaires » →
  « Exécuter quand même ».
- Windows 10/11 64 bits uniquement.
