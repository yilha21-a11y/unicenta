# Connecter un téléphone (prise de commande)

Les serveurs prennent les commandes sur leur téléphone, dans le navigateur
(Safari, Chrome), sur le Wi-Fi du restaurant. Rien à installer sur le téléphone.

## À faire une fois par PC

1. **Licence** avec la fonction « mobile » (voir `LICENCE.md`).
2. **Mot de passe du serveur** : *Maintenance > Utilisateurs*. Un compte sans
   mot de passe ne peut pas se connecter depuis un téléphone.
3. **Droit « téléphone »** : *Maintenance > Rôles*, case « Accès depuis le
   téléphone (mobile.Access) » (cochée d'office pour Administrator, Manager et
   Employee). Pour l'envoi en cuisine depuis le téléphone, le rôle doit avoir
   `sales.PrintRemote` ; pour l'addition, `sales.PrintTicket`.
4. **Pare-feu Windows** : au premier lancement, Windows demande « Autoriser
   l'accès » pour OLPOS → cochez **Réseaux privés** puis Autoriser (mot de
   passe administrateur du PC). Le Wi-Fi du restaurant doit être en réseau
   **Privé** (*Paramètres > Réseau et Internet > Wi-Fi*). Sinon, dans une
   invite de commandes administrateur :

   ```
   netsh advfirewall firewall add rule name="OLPOS mobile" dir=in action=allow protocol=TCP localport=8095 profile=private
   ```

## Sur le téléphone

1. Adresse du PC : commande `ipconfig` sur le PC, ligne « Adresse IPv4 »
   (ex. `192.168.1.20`).
2. Ouvrez `http://192.168.1.20:8095/` (ou `https://…` si HTTPS est activé,
   recommandé : voir `HTTPS.md`).
3. iPhone : *Partager > Sur l'écran d'accueil* pour avoir l'icône OLPOS.

## Fonctionnement

- Le téléphone choisit des produits ; **prix et taxes sont calculés par la
  caisse**.
- Chaque modification porte l'**identifiant de la commande et sa version** :
  si la commande a changé ailleurs, ou si la table a été encaissée puis
  rouverte, rien n'est écrasé et le téléphone affiche la commande à jour.
- Table ouverte à la caisse : le téléphone consulte mais ne modifie pas.
- Coupure Wi-Fi ou erreur de la caisse (réponse 500…) : la demande est
  **gardée** et renvoyée telle quelle (même identifiant, jamais en double).
- Si le navigateur ne peut pas enregistrer (navigation privée, stockage
  bloqué), un message rouge le dit : une demande en attente serait perdue en
  fermant la page.
- Retrait d'un droit, d'un mot de passe ou d'un compte à la caisse : les
  téléphones concernés sont déconnectés en 2 secondes au plus.
- Cuisine : bouton **Cuisine** (voir `IMPRIMANTE-CUISINE.md`) ; **Addition** :
  addition provisoire imprimée à la caisse. **L'encaissement se fait toujours
  à la caisse.**

## Désactiver

Relancer l'installateur et décocher la case, ou `api.enabled=false` dans
`%LOCALAPPDATA%\OLPOS5\olpos.properties`.
