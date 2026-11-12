# OLPOS License Manager - guide de l'administrateur

Outil **administrateur** qui crée et renouvelle les licences OLPOS.
**Ne l'installez jamais chez un restaurant** et ne publiez jamais son
installateur avec OLPOS.

## Première ouverture

1. Choisissez **Créer une nouvelle clé de signature** (ou *Restaurer une clé
   sauvegardée* sur un nouveau PC administrateur).
2. Choisissez le **mot de passe du gestionnaire** (10 caractères minimum,
   lettres + chiffres/symboles). Il protège l'accès et chiffre la clé privée.
3. **Immédiatement** : onglet *Sécurité et sauvegardes* → **Sauvegarder la clé
   privée** (phrase de passe distincte, à noter). Rangez le fichier
   `.olposkey` hors ligne (clé USB rangée ailleurs). **Sans cette clé, aucun
   renouvellement ne pourra être signé** et il faudrait réinstaller OLPOS
   partout avec une nouvelle clé publique.
4. **Exporter la clé publique (pour OLPOS)** → fichier
   `trusted-keys.properties`, à placer dans
   `src/main/resources/com/olpos/license/` avant de construire l'installateur
   OLPOS (`build-installers.bat`). Cette clé est publique : elle ne permet pas
   de créer de licences.

Données : `%APPDATA%\OLPOS License Manager\olpos-license-manager.dat`,
entièrement chiffré (AES-256-GCM, clé dérivée du mot de passe par PBKDF2).
La clé privée n'est jamais écrite en clair, ni dans le dépôt Git, ni dans un
installateur.

## Clients et licences

- Onglet **Clients et restaurants** : ajouter, modifier, rechercher.
- Onglet **Licences** → **Nouvelle licence** :
  - début (00:00 UTC) ; durée **30 jours, 3 mois, 6 mois, 1 an** ou dates
    personnalisées ; la **fin est incluse** (jusqu'à 23:59:59 UTC) ;
  - fonctions : ventes (toujours), téléphone, cuisine ;
  - postes maximum (0 = sans limite) ;
  - code du poste (facultatif : licence liée à un PC ; code affiché dans
    *Activation / Licence* d'OLPOS).
- Le **numéro de série** est tiré au hasard (80 bits) : il ne permet pas de
  fabriquer une licence. Seul le code signé compte.
- **Code et fichier** : copiez le code d'activation ou enregistrez le fichier
  `.olposlic` à envoyer au restaurant.

## Renouveler ou transférer

Sélectionnez la licence → **Renouveler / transférer** : même numéro de série,
révision + 1 ; la date de début proposée suit la fin précédente. Pour un
nouveau PC, saisissez le nouveau code de poste. **Historique** liste toutes
les révisions. Côté restaurant, il suffit d'importer la nouvelle licence.

Listes : **Actives / Futures / Expirées**, recherche, **Exporter en CSV**
(Excel, séparateur `;`).

## Sauvegarder

- **Sauvegarder toutes les données** (`.olposlm`, chiffré avec le mot de passe
  actuel) : régulièrement, sur un autre support.
- **Restaurer une sauvegarde** : les données actuelles sont gardées à côté
  (`.avant-restauration`).
- **Changer le mot de passe** : les anciennes sauvegardes gardent l'ancien.

## Rappels de sécurité

- Un mot de passe oublié ne peut pas être récupéré : restaurez alors la clé
  (`.olposkey` + sa phrase de passe) dans une nouvelle configuration.
- Les licences OLPOS sont vérifiées hors ligne : une licence délivrée ne peut
  pas être annulée avant sa date de fin. Préférez des durées courtes pour
  les clients incertains.
