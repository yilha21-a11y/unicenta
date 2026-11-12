# Configurer et tester une imprimante cuisine

## 1. Choisir quels produits vont en cuisine

Dans *Stock > Produits*, champ **Imprimante** (« Print to ») de chaque produit :

| Valeur | Effet |
|---|---|
| `0` | pas d'envoi en cuisine (boissons, desserts au comptoir…) |
| `1` à `6` | envoyé sur l'imprimante 1 à 6 (ex. `2` = cuisine, `3` = bar) |

Attention : uniCenta 5.0 met `1` par défaut. Mettez `0` aux produits qui ne
doivent pas partir en cuisine.

## 2. Déclarer l'imprimante

*Configuration > Périphériques*, imprimante 2 (ou 3…) :

- **Imprimante réseau ESC/POS (TCP)** : type `epson` (ou le modèle ESC/POS
  adapté), connexion **socket**, port : `adresse:9100`
  (ex. `192.168.1.50:9100`). Le port 9100 est la valeur habituelle et peut
  être changé (`192.168.1.50:9101`).
- **Imprimante Windows** (pilote installé) : type `printer`, choisir
  l'imprimante dans la liste.
- **Écran** (`screen`) : pour essayer sans matériel.

Le fichier de configuration contient alors par exemple
`machine.printer.2=epson:socket,192.168.1.50:9100`.

## 3. Envoyer en cuisine

- **Caisse** : table ouverte → bouton **Envoyer** (cuisine). La caisse
  enregistre la commande puis utilise le **même service** que les téléphones.
- **Téléphone** : bouton **Cuisine**.

Seuls les **nouveaux** articles « à envoyer » partent. Un complément porte
**AJOUT**, une réimpression **DUPLICATA**. Chaque bon porte le numéro de
commande (1 à 100, commun au ticket client), la date, l'heure et la table
(ou « A EMPORTER »), et un bon par imprimante concernée.

## 4. Statuts d'un envoi

| Statut | Signification | Que faire |
|---|---|---|
| en cours | impression en cours | attendre |
| envoyé | données transmises sans erreur ; **l'imprimante ne confirme pas** la sortie du papier (une connexion TCP réussie n'est pas une preuve d'impression) | rien |
| confirmé | l'imprimante confirme (affichage écran seulement) | rien |
| **échec** | **rien** n'a atteint l'imprimante (injoignable, pilote en erreur) ; les articles redeviennent « à envoyer » | vérifier l'imprimante puis renvoyer |
| **à vérifier** | erreur ou délai **pendant** l'envoi, ou caisse arrêtée : le bon a pu sortir ou non | aller voir en cuisine, puis « Le bon est sorti » ou « Réimprimer » (DUPLICATA) |

OLPOS **ne réimprime jamais automatiquement** un bon dont le résultat est
incertain. Les erreurs du pilote Windows sont remontées (message et statut),
plus seulement dans le journal.

## 5. Tester

1. Mettez l'imprimante 2 sur `screen` : ouvrez une table, ajoutez un plat
   (Imprimante = 2), bouton Envoyer → le bon s'affiche, statut « confirmé ».
2. Passez en réseau (`socket`) : nouvel article → « envoyé » ; vérifiez le
   papier.
3. Éteignez l'imprimante : nouvel article → message « ne répond pas, rien n'a
   été imprimé », l'article reste à envoyer.

Vérifié automatiquement : sélection des nouvelles lignes, AJOUT/DUPLICATA,
échec certain, résultat incertain, envoi répété sans double impression,
imprimante réseau (serveur TCP de test). **Non vérifié** : impression papier
réelle sur une imprimante ESC/POS ou un pilote Windows (aucune imprimante
disponible pendant le développement).
