# Installer et configurer OLPOS 5

OLPOS 5 est une caisse pour restaurants, version dérivée d'uniCenta oPOS 5.0
(licence GNU GPL v3). Java est inclus : rien d'autre à installer.

## 1. Installer

1. Lancez `OLPOS5-Setup.exe`. Aucun droit administrateur n'est demandé.
2. Suivant → **Options** (case « Activer la prise de commande sur téléphone »,
   cochée par défaut) → Installer → Terminer.
3. Raccourcis créés : Bureau et menu Démarrer (« OLPOS », « OLPOS - Guides »,
   « OLPOS - Licences »). OLPOS apparaît dans *Paramètres > Applications*
   sous le nom **OLPOS 5** (désinstallation possible à cet endroit).

Windows peut afficher « Windows a protégé votre ordinateur » (SmartScreen),
car l'installateur n'est pas signé : *Informations complémentaires > Exécuter
quand même*.

## 2. Où sont les fichiers

| Quoi | Où |
|---|---|
| Programme (remplacé à chaque mise à jour) | `%LOCALAPPDATA%\Programs\OLPOS5` |
| Données : `olpos.properties`, base `database\`, `derby.log`, licence, certificat HTTPS | `%LOCALAPPDATA%\OLPOS5` |

- Les données ne sont **jamais** supprimées par une mise à jour ni par la
  désinstallation. Pour repartir de zéro : sauvegardez puis supprimez vous-même
  `%LOCALAPPDATA%\OLPOS5`.
- OLPOS 5 n'utilise jamais `~\unicentaopos.properties` ni `~\.unicenta`
  (fichiers d'uniCenta) : une caisse uniCenta ou OLPOS 3.81 déjà installée
  sur le PC n'est pas touchée et peut rester ouverte en même temps.
- Les bases OLPOS 3.81 / uniCenta 3.x ne sont pas reprises automatiquement
  (schéma différent) : la migration de données n'a pas été réalisée ni testée.

## 3. Premier lancement

1. « A working database cannot be detected » / création de base : acceptez.
   La base est créée dans `%LOCALAPPDATA%\OLPOS5\database`.
2. **Licence** : sans licence, OLPOS s'ouvre mais bloque les nouvelles ventes.
   Bouton **Activation / Licence** sur l'écran de connexion (voir `LICENCE.md`).
3. Comptes d'exemple (Administrator, Manager, Employee, Guest) **sans mot de
   passe** : définissez-en un dans *Maintenance > Utilisateurs*.
4. *Configuration* :
   - nom du restaurant et pied de ticket : *Configuration > Ticket* (en-têtes
     `tkt.header1`…) ; le logo imprimé se remplace dans *Maintenance >
     Ressources > Printer.Ticket.Logo* ;
   - devise et formats : *Configuration > Locale* (la devise des téléphones
     suit ce réglage, rien n'est codé en dur) ;
   - imprimantes : voir `IMPRIMANTE-CUISINE.md`.

## 4. Fichier de configuration (avancé)

`%LOCALAPPDATA%\OLPOS5\olpos.properties` : un fichier par installation.

| Réglage | Défaut | Rôle |
|---|---|---|
| `machine.ticketsbag` | `restaurant` | plan de salle et tables |
| `api.enabled` | `true` | page téléphone |
| `api.port` / `api.bind` | `8095` / `0.0.0.0` | adresse de la page téléphone |
| `api.https.enabled` | `false` | HTTPS (voir `HTTPS.md`) |
| `user.timezone` | (vide = Windows) | fuseau horaire de la caisse |

## 5. Mettre à jour

Lancez le nouvel `OLPOS5-Setup.exe` : le programme est remplacé, les données,
la licence et les réglages sont conservés (le choix « téléphone » précédent
est repris).
