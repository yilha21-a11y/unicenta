# HTTPS pour les téléphones

Sans HTTPS, le mot de passe du serveur et le jeton de connexion circulent en
clair sur le Wi-Fi. Avec HTTPS, ils sont chiffrés **et** le téléphone vérifie
qu'il parle bien à la caisse du restaurant.

OLPOS ne désactive jamais la vérification des certificats. Comme une caisse
sur un réseau local n'a pas de nom Internet, OLPOS utilise une **autorité de
certification locale** propre au restaurant, installée une fois sur chaque
téléphone.

## 1. Sur le PC de la caisse (une fois)

1. Fermez OLPOS.
2. Double-cliquez sur `OLPOS-HTTPS.cmd`
   (`%LOCALAPPDATA%\Programs\OLPOS5`). Il crée dans
   `%LOCALAPPDATA%\OLPOS5\https` :
   - `olpos-ca.cer` : autorité locale (certificat **public**, pour les téléphones) ;
   - `olpos-https.p12` : certificat du PC, avec ses adresses IP et son nom
     (825 jours, limite d'Apple) ;
   - `password.txt` : mot de passe aléatoire de ces fichiers ;
   - `empreinte.txt` : empreinte SHA-256 de l'autorité ;
   - `ca.p12` : clé privée de l'autorité. **Ne la copiez nulle part** : qui
     la possède pourrait se faire passer pour la caisse auprès des téléphones.

   Il ajoute aussi `api.https.enabled=true` à `olpos.properties`.
3. Pare-feu (administrateur), pour la page du certificat :

   ```
   netsh advfirewall firewall add rule name="OLPOS certificat" dir=in action=allow protocol=TCP localport=8094 profile=private
   ```
4. Relancez OLPOS.

## 2. Sur chaque téléphone (une fois)

1. Ouvrez `http://<adresse du PC>:8094/`. Cette page ne sert **que** le
   certificat public et son empreinte : aucun mot de passe n'y transite.
2. Vérifiez que l'empreinte affichée est **identique** à celle de
   `empreinte.txt` sur la caisse, puis téléchargez le certificat.
3. **iPhone** :
   - *Réglages > Profil téléchargé > Installer* ;
   - puis *Réglages > Général > Informations > Réglages des certificats* :
     activez la confiance totale pour « OLPOS - autorité locale ».
4. **Android** : *Paramètres > Sécurité > Chiffrement et identifiants >
   Installer un certificat > Certificat CA*.
5. Ouvrez ensuite `https://<adresse du PC>:8095/`.

## Si l'adresse IP du PC change

Le certificat contient les adresses du PC au moment de sa création. Fixez
l'adresse du PC dans la box (bail DHCP réservé), ou relancez `OLPOS-HTTPS.cmd`
(il propose de remplacer le certificat ; les téléphones devront réinstaller
l'autorité).

## Réglages (olpos.properties)

| Réglage | Défaut |
|---|---|
| `api.https.enabled` | `false` |
| `api.https.keystore` | `%LOCALAPPDATA%\OLPOS5\https\olpos-https.p12` |
| `api.https.password` | contenu de `https\password.txt` |
| `api.https.caport` | `8094` (`0` : pas de page du certificat) |

## Limites

- La page du certificat est en HTTP : vérifier l'empreinte protège contre un
  certificat substitué sur le Wi-Fi.
- Testé automatiquement (connexion vérifiée, refus sans l'autorité, nom
  vérifié). **Non testé sur un vrai iPhone/Android** pendant le développement.
