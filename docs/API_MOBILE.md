# API mobile OLPOS (version 1)

> OLPOS est une version dérivée d'uniCenta oPOS 3.81 (voir [OLPOS.md](OLPOS.md)).

Cette API permet à un téléphone (iPhone ou Android) de se connecter à la caisse OLPOS
sur le réseau local. **Le téléphone n'accède jamais à la base de données** : il
passe uniquement par l'API, qui applique les règles de la caisse.

> **Page web de prise de commande** : la caisse sert aussi une page web pour
> téléphone (iPhone et Android), sans rien installer : `http://<IP-du-PC>:8095/`.
> Connexion du serveur, plan de salle, ajout de produits, quantités, notes
> cuisine, modification des lignes, envoi à la caisse. Une demande sans réponse
> (coupure Wi-Fi) est gardée sur le téléphone et renvoyée telle quelle (même
> `requestId`, donc sans doublon) ; en cas de conflit, le brouillon est conservé.
> Fichiers : `src-pos/com/openbravo/pos/api/web/` (aucune ressource externe).
> Sur iPhone : Safari > Partager > « Sur l'écran d'accueil » pour l'ouvrir comme une appli.
> Il n'existe pas d'application Android native.

État actuel (étapes 0 à 4) :

| Fonction | État |
|---|---|
| Compilation Java 8+ (testée avec JDK 17) | ✅ |
| Connexion d'un serveur, jeton, permissions | ✅ |
| Catégories, produits, prix TTC | ✅ |
| Salles, tables, table occupée | ✅ |
| Création / modification de commande (produits, quantités, notes) | ✅ étape 4 |
| Protection caisse ↔ téléphone, conflits, requêtes répétées | ✅ étape 4 |
| Envoi en cuisine (statuts, sans double impression) | ✅ étape 5 |
| Addition provisoire + imprimantes réseau (TCP 9100) | ✅ étape 6 |
| Application Android | ⏳ étape 7 |
| Encaissement | ❌ reste à la caisse (choix v1) |

---

## 1. Activer l'API (par restaurant)

L'API est **désactivée par défaut**. Elle se règle dans le fichier de
configuration de la caisse (`unicentaopos.properties`, ou celui passé en
argument à `start.bat`). Aucune adresse IP ni aucun mot de passe n'est écrit
dans le code.

```properties
api.enabled=true          # indispensable pour démarrer l'API
api.port=8095             # port TCP (à ouvrir dans le pare-feu Windows)
api.bind=0.0.0.0          # 0.0.0.0 = toutes les cartes réseau ; ou l'IP du PC caisse
api.session.minutes=240   # déconnexion après 4 h sans activité
api.login.maxattempts=5   # essais de mot de passe avant blocage
api.login.lockseconds=60  # durée du blocage
api.users.list=true       # le téléphone peut afficher la liste des serveurs
api.threads=4
```

Fuseau horaire de la caisse (optionnel, par restaurant) :

```properties
user.timezone=America/Los_Angeles   # vide ou absent : la caisse suit le fuseau de Windows
```

À utiliser quand Java et Windows n'appliquent pas les mêmes règles (cas de
Vancouver en 2026 : Windows « Pacific Time » = UTC−8, Java = UTC−7). Le fuseau
choisi doit donner la même heure que les téléphones du restaurant.

L'API démarre et s'arrête avec la caisse. Si elle ne peut pas démarrer (port
déjà utilisé…), la caisse affiche un avertissement et **continue à fonctionner
normalement**.

## 2. Qui peut se connecter ?

Un serveur peut se connecter depuis un téléphone seulement si :

1. son compte a un **mot de passe** (les comptes sans mot de passe sont refusés) ;
2. son rôle contient la permission **`mobile.Access`**.

Les permissions sont celles de la caisse : l'API relit le XML du rôle avec
`AppUser.fillPermissions()` et utilise `AppUser.hasPermission()`.

- **Base neuve** : `mobile.Access` est déjà donnée aux rôles Administrator,
  Manager et Employee (pas à Guest).
- **Base existante** : dans la caisse, *Administration > Maintenance > Roles*,
  ouvrir le rôle, cocher **« Phone access (mobile.Access) »** au-dessus du texte,
  puis enregistrer. La case ajoute (ou retire) la ligne
  `<class name="mobile.Access"/>` dans le XML du rôle.

## 3. Adresses de l'API

Toutes les réponses sont en JSON (UTF-8). Les erreurs ont la forme
`{"error": {"code": "...", "message": "..."}}`, avec un message en français.

| Méthode | Adresse | Jeton | Description |
|---|---|---|---|
| GET | `/api/v1/health` | non | État et version |
| GET | `/api/v1/users` | non | Serveurs autorisés (id, nom) |
| POST | `/api/v1/auth/login` | non | `{"userId": "...", "password": "..."}` → jeton |
| POST | `/api/v1/auth/logout` | oui | Invalide le jeton |
| GET | `/api/v1/me` | oui | Serveur connecté et permissions |
| GET | `/api/v1/catalog` | oui | Catégories + tous les produits |
| GET | `/api/v1/catalog/categories` | oui | Catégories (avec `parentId`) |
| GET | `/api/v1/catalog/products?category=ID` | oui | Produits d'une catégorie |
| GET | `/api/v1/floors` | oui | Salles, tables, occupation |

Le jeton s'envoie dans l'en-tête `Authorization: Bearer <jeton>`.

Codes d'erreur : `400` requête invalide, `401` non connecté / mauvais mot de
passe, `403` permission manquante ou compte sans mot de passe, `404`, `405`,
`429` trop d'essais, `500` erreur côté caisse.

**Prix** : `priceSell` est le prix HT tel qu'il est stocké. `priceSellTaxed` et
`priceDisplay` sont calculés avec les taxes de la caisse (`TaxesLogic`),
exactement comme sur l'écran de vente.

**Tables** : une table est `occupied: true` quand une commande en cours existe
pour elle dans `SHAREDTICKETS`, comme sur le plan de salle de la caisse.

### 3.1 Commandes (étape 4)

La caisse doit être en mode restaurant (`machine.ticketsbag=restaurant`).

| Méthode | Adresse | Description |
|---|---|---|
| GET | `/api/v1/tables/{id}/order` | Commande en cours : `version`, lignes, totaux, `lockedBy` |
| POST | `/api/v1/tables/{id}/order/changes` | Créer ou modifier la commande |

Corps d'une modification :

```json
{
  "requestId": "3f1c2a9e-0b7d-4c51-9a55-1d2e3f4a5b6c",
  "baseVersion": 2,
  "operations": [
    {"op": "add", "productId": "p-steak", "quantity": 2, "note": "saignant"},
    {"op": "setQuantity", "lineId": "…", "quantity": 3},
    {"op": "setNote", "lineId": "…", "note": "sans sel"},
    {"op": "remove", "lineId": "…"}
  ]
}
```

- **`requestId`** : un identifiant unique (UUID) par modification. Si le Wi-Fi
  coupe, le téléphone renvoie **la même requête avec le même `requestId`** : elle
  ne sera pas appliquée deux fois (réponse `"duplicate": true`). Cela reste vrai
  après un redémarrage de la caisse, car les derniers `requestId` sont gardés
  dans la commande elle-même.
- **`baseVersion`** : la `version` lue avec `GET …/order` (0 si la table est
  libre). Si la commande a changé entre-temps (autre téléphone, caisse), **rien
  n'est enregistré** : réponse `409 version_conflict` avec un message indiquant
  qui a modifié la commande et quand, plus la commande à jour dans `order`.
- **Prix** : le téléphone n'envoie jamais de prix. La caisse prend le prix de
  vente de la base et la taxe du produit (`TaxesLogic`), exactement comme
  `JPanelTicket` ; les totaux sont recalculés à chaque modification.
- **Tout ou rien** : si une opération est invalide, aucune n'est appliquée.
- **Notes cuisine** : propriété de ligne `notes`, la même que le bouton
  « note » de la caisse (`script.AddLineNote`). 200 caractères au plus.
- **Quantités** : nombres entiers de 1 à 999. Produits refusés depuis le
  téléphone (à saisir à la caisse) : prix variable, au poids, auxiliaires,
  attributs obligatoires.
- **Lignes déjà envoyées en cuisine** (`sent: true`) : non modifiables depuis le
  téléphone ; on ajoute une nouvelle ligne.

Permissions : `mobile.Access` pour consulter et **ajouter** ; `sales.EditLines`
(comme à la caisse) pour **modifier ou supprimer** une ligne. Une suppression
est journalisée dans `LINEREMOVED`, comme à la caisse.

| Code | Signification pour le téléphone |
|---|---|
| `409 version_conflict` | Commande modifiée ailleurs : afficher `order` (à jour) et refaire la saisie |
| `409 order_closed` | Commande encaissée / déplacée / annulée à la caisse |
| `423 table_locked` | Table ouverte à l'écran de la caisse (`lockedBy.who`) : réessayer plus tard |
| `409 table_busy` | Un autre téléphone modifie la table au même instant : réessayer |
| `409 line_already_sent` | Ligne déjà envoyée en cuisine |
| `409 request_id_reused` | Même `requestId` pour une demande différente (bug côté téléphone) |
| `422 product_not_supported` | Produit à saisir à la caisse |

### 3.1 bis Envoi en cuisine (étape 5)

| Méthode | Adresse | Corps |
|---|---|---|
| POST | `/api/v1/tables/{id}/kitchen/send` | `{"sendId": "<uuid>"}` |
| POST | `/api/v1/tables/{id}/kitchen/{sendId}/resolve` | `{"action": "printed"}` ou `{"action": "reprint"}` |

Permission : `sales.PrintKitchen`. Seules les **nouvelles lignes cuisine**
(`kitchenStatus: "a_envoyer"`) partent, avec le modèle `Printer.TicketKitchen`
de la base et les imprimantes de la caisse (`machine.printer.2` par défaut).
Le bon imprime maintenant les **notes** et le nom de la table en gras.

Statuts d'un envoi (`order.kitchenSends`) :

| Statut | Signification |
|---|---|
| `envoye` | Bon transmis à l'imprimante sans erreur, **sans confirmation** du matériel (cas des imprimantes ESC/POS) |
| `confirme` | L'imprimante confirme (aujourd'hui : affichage écran de la caisse) |
| `a_verifier` | Erreur ou délai dépassé **pendant** l'impression, ou caisse arrêtée au milieu : le bon a pu sortir |
| `non_imprime` | L'impression n'a jamais commencé (imprimante occupée) : lignes toujours « à envoyer » |
| `confirme_manuellement` / `reimpression_demandee` | Choix du serveur après vérification |

Garanties :
- les lignes sont marquées « à vérifier » **avant** d'imprimer ; une ligne
  « à vérifier » n'est **jamais** renvoyée automatiquement : le serveur choisit
  « Le bon est sorti » ou « Réimprimer » ;
- le même `sendId` renvoyé (coupure Wi-Fi) est reconnu : **aucune double impression** ;
  la page web ne renvoie rien d'elle-même et affiche « à vérifier » avec un bouton
  **Vérifier** ;
- imprimante cuisine non configurée (`Not defined`) : refus **avant** impression
  (`503 kitchen_printer_not_ready`), rien n'est modifié ;
- textes de la commande protégés dans le XML du bon (`<`, `&`, `$`, `#`).

Base existante : le nouveau modèle (notes) ne s'applique qu'aux nouvelles bases.
Sur une base existante, copier `Printer.TicketKitchen.xml` dans *Administration >
Ressources > Printer.TicketKitchen*. Le bouton cuisine de la caisse fonctionne
comme avant et ignore les lignes « à vérifier ».

### 3.1 ter Addition provisoire (étape 6)

| Méthode | Adresse | Corps |
|---|---|---|
| POST | `/api/v1/tables/{id}/bill/print` | `{"printId": "<uuid>"}` |

Permission : `sales.PrintTicket`. Imprime le détail, les taxes et le total de la
table (calculés par la caisse) sur l'imprimante de tickets (`machine.printer`),
**sans encaisser** et sans modifier la commande. Mêmes statuts que la cuisine
(`confirme`, `envoye`, `a_verifier`, `non_imprime`). Le même `printId` renvoyé
après une coupure Wi-Fi n'imprime pas une seconde addition ; une nouvelle
addition = un nouveau `printId` (bouton « Réimprimer », choix explicite).
Refus si la table est ouverte à la caisse (l'addition pourrait être incomplète).

Modèle : la ressource `Printer.TicketBill` de la base si elle existe
(*Administration > Ressources*, pour la personnaliser), sinon le modèle
« ADDITION PROVISOIRE » fourni avec la caisse
(`src-pos/com/openbravo/pos/templates/Printer.TicketBill.xml`).

### Imprimantes réseau (ESC/POS en TCP)

Nouvelle connexion `socket` pour les imprimantes de tickets réseau (port 9100) :

```properties
machine.printer=epson:socket,192.168.1.50:9100     # tickets / additions
machine.printer.2=epson:socket,192.168.1.51:9100   # cuisine
```

Dans la caisse : *Configuration > Périphériques*, type `epson` (ou `star`…),
connexion **`socket`**, puis saisir `adresse:9100` dans le champ du port.
Avant chaque impression demandée depuis un téléphone, la caisse vérifie que
l'imprimante répond ; sinon refus clair (« ne répond pas… rien n'a été imprimé »).
Une imprimante réseau ne confirme pas la sortie du papier : statut `envoye`.

En mode test, les bons s'affichent à l'écran : à la caisse (Administrator),
menu **Printers**.

### 3.1 quater Numéro de commande 1 à 100

Chaque commande reçoit un numéro visible commun au ticket client, à l'addition
et au bon cuisine (« COMMANDE N° 42 ») : `order.orderNumber` dans
`GET …/order` et `orderNumber` sur chaque table de `GET /floors`.

- Séquence 1, 2 … 100 puis 1 ; jamais remise à zéro chaque jour ni au
  redémarrage. Compteur unique en base (ligne `olpos.order.counter` de la table
  `RESOURCES`), partagé par la caisse, l'API et tous les postes, incrémenté
  de façon atomique (ligne verrouillée dans la transaction).
- Attribué à la première sauvegarde contenant un article (dans la même
  transaction : pas de numéro perdu en cas de conflit), puis conservé
  (modifications, ajouts, cuisine, addition, paiement, réimpression).
- Une requête répétée (même `requestId`) ne crée ni commande ni numéro.
- Le numéro visible n'identifie pas la commande (il est réutilisé) : la
  commande reste identifiée par la table, `orderId` et `version`.
- Distinct du numéro de ticket comptable (`TICKETID`), inchangé.
- Tickets : date, heure et table (ou « A EMPORTER ») ; « AJOUT » sur un bon
  cuisine complémentaire, « DUPLICATA » sur une réimpression.
- Bases existantes : bloc ajouté aux modèles `Printer.Ticket`,
  `Printer.TicketKitchen`, `Printer.TicketPreview`, `Printer.ReprintTicket`,
  `Printer.TicketBill` (insertion seulement ; désactivable avec
  `olpos.ordernumber.templates=false`). Les commandes en cours sans numéro en
  reçoivent un à leur prochaine sauvegarde.

Guide utilisateur : [OLPOS_Guide_Connexion_Telephone.pdf](OLPOS_Guide_Connexion_Telephone.pdf)
(source : `docs/guide/guide.html`, régénération : `python docs/guide/build_guide.py`).

### 3.2 Comment la caisse et les téléphones restent synchronisés

1. **Verrou de table** : tant qu'une table est ouverte à l'écran de la caisse,
   les téléphones ne peuvent pas la modifier (`423`). Inversement, la caisse
   attend au plus 3 s qu'une modification venant d'un téléphone se termine
   avant d'ouvrir la table, sinon elle affiche « table en cours de modification
   depuis un téléphone ».
2. **Numéro de version** : chaque enregistrement (caisse ou téléphone) vérifie,
   dans une transaction qui verrouille la ligne en base, que la commande n'a pas
   changé depuis sa lecture. Cela protège aussi plusieurs caisses partageant une
   base MySQL. Si la caisse rencontre un conflit, elle **n'écrase rien** et
   demande quelle version garder (par défaut : la version enregistrée).
3. **Plan de salle** : il se rafraîchit tout seul quand un téléphone modifie une
   commande, s'il est affiché à l'écran de la caisse.

La version est rangée dans les propriétés du ticket : **aucun changement du
schéma de la base**. Une commande créée par l'ancienne caisse vaut version 1.

## 4. Tester sur votre PC

Prérequis : un JDK 8 ou plus récent (testé avec Temurin 17) dans le `PATH`.

```bat
build.bat                 :: compile et crée unicentaopos.jar
test.bat                  :: lance les 99 tests automatiques
test-env-setup.bat        :: crée la base et la configuration de TEST dans test-env\
start-test.bat            :: lance la caisse sur l'environnement de TEST
```

`test-env-setup.bat` affiche le mot de passe du serveur de démonstration
« Serveur Test ». `test-env-setup.bat --reset` recrée une base de test neuve.
**Ces scripts ne touchent jamais votre installation** (`%USERPROFILE%\unicentaopos.properties`
et sa base) : l'outil refuse explicitement de travailler sur cette base.

Puis, dans un autre terminal (ou depuis un téléphone sur le même Wi-Fi, en
remplaçant `127.0.0.1` par l'adresse IP du PC) :

```bat
curl http://127.0.0.1:8095/api/v1/health
curl http://127.0.0.1:8095/api/v1/users
curl -X POST http://127.0.0.1:8095/api/v1/auth/login -d "{\"userId\":\"<id>\",\"password\":\"<mot de passe>\"}"
curl -H "Authorization: Bearer <jeton>" http://127.0.0.1:8095/api/v1/floors
curl -H "Authorization: Bearer <jeton>" http://127.0.0.1:8095/api/v1/catalog
```

Créer puis modifier une commande (remplacer `<jeton>`) :

```bat
curl -X POST http://127.0.0.1:8095/api/v1/tables/3/order/changes -H "Authorization: Bearer <jeton>" -d "{\"requestId\":\"essai-0001\",\"baseVersion\":0,\"operations\":[{\"op\":\"add\",\"productId\":\"p-salade\",\"quantity\":2,\"note\":\"sans croutons\"}]}"
curl -H "Authorization: Bearer <jeton>" http://127.0.0.1:8095/api/v1/tables/3/order
```

Renvoyer exactement la première commande montre `"duplicate":true` ; la
renvoyer avec `"baseVersion":0` et un autre `requestId` montre le conflit.

### 4.1 Depuis votre téléphone

Il n'y a pas encore d'application : le téléphone sert de client HTTP.

1. Le téléphone et le PC doivent être sur le **même Wi-Fi**. Adresse du PC :
   `ipconfig` → « Carte réseau sans fil Wi-Fi » → Adresse IPv4 (ex. `192.168.1.133`).
2. **Pare-feu Windows** (une fois, dans une invite de commandes *administrateur*) :
   - si le Wi-Fi est en profil « Public », le passer en « Privé » (Paramètres >
     Réseau et Internet > Wi-Fi > *votre réseau* > Réseau privé) ;
   - puis autoriser le port de l'API sur les réseaux privés uniquement :
     ```bat
     netsh advfirewall firewall add rule name="OLPOS API mobile (test)" dir=in action=allow protocol=TCP localport=8095 profile=private
     ```
     (pour la retirer : `netsh advfirewall firewall delete rule name="OLPOS API mobile (test)"`)
3. Dans le navigateur du téléphone : `http://192.168.1.133:8095/api/v1/health`
   puis `…/api/v1/users`. Si la page s'affiche, la connexion fonctionne.
4. Pour les adresses avec jeton (commandes, catalogue, tables), le navigateur ne
   suffit pas : utilisez une application « client HTTP » (par ex. *HTTP Shortcuts*,
   *Postman*, ou *Termux* avec `curl`) et reprenez les commandes ci-dessus en
   remplaçant `127.0.0.1` par l'adresse du PC.
5. Pendant ce temps, ouvrez/fermez des tables sur la caisse de test pour voir
   les refus `423` et le plan de salle se mettre à jour.

## 5. Imprimantes (préparation)

Les imprimantes restent celles de la caisse, réglées par restaurant dans
`unicentaopos.properties` : `machine.printer` (reçus) et `machine.printer.2`
(cuisine, utilisée par `Printer.TicketKitchen.xml`), jusqu'à `machine.printer.6`.
L'environnement de test les règle sur `screen` (affichage à l'écran) tant que le
matériel n'est pas connu. Les types disponibles aujourd'hui sont : écran,
imprimante Windows (`printer`), ESC/POS sur port série ou fichier, JavaPOS.
Une connexion ESC/POS **réseau (TCP 9100)** sera ajoutée à l'étape 6.

## 6. Sécurité

- API désactivée par défaut, écoute configurable (`api.bind`).
- Jetons aléatoires de 256 bits, expiration après inactivité, déconnexion.
- Blocage temporaire après plusieurs mots de passe faux ; même message pour un
  compte inconnu et un mauvais mot de passe.
- Comptes sans mot de passe refusés ; permission `mobile.Access` obligatoire.
- Les mots de passe et jetons ne sont jamais écrits dans les journaux.
- **Limite connue** : HTTP simple, sans chiffrement. À utiliser uniquement sur un
  réseau local de confiance (Wi-Fi du personnel séparé du Wi-Fi clients). Les
  mots de passe de la caisse restent hachés en SHA-1 sans sel (existant uniquement).

## 7. Étapes suivantes (rappel du plan)

- **Étape 7** : application Android (Kotlin, Jetpack Compose).

## 8. Fichiers

- `src-pos/com/openbravo/pos/api/` : `ApiServer` (HTTP), `ApiConfig` (réglages),
  `ApiSessions` (jetons, blocage), `MobileDataService` (accès aux données via
  les classes de la caisse), `MobileOrderService` (commandes),
  `ProcessedRequests` (anti-doublon), `Json`.
- `src-pos/com/openbravo/pos/sales/restaurant/` : `TableLocks` (verrou de table
  partagé caisse/API), `TableTicketStore` (commandes versionnées),
  `OrderConflictException`, et `JTicketsBagRestaurantMap` (plan de salle de la
  caisse, qui utilise désormais le verrou et la version).
- `src-pos/com/openbravo/pos/forms/AppConfig.java` : fichier de configuration
  « actif » de l'instance (`setActiveConfigFile` / `loadActive`), utilisé par les
  écrans de paiement, la numérotation des tickets, le pavé numérique et les
  promotions, au lieu de `~/unicentaopos.properties` codé en dur.
- `src-pos/com/openbravo/pos/forms/JRootApp.java` : démarrage et arrêt de l'API.
- `src-pos/com/openbravo/pos/forms/AppViewConnection.java` :
  `createAdditionalSession()`, une connexion supplémentaire qui n'ouvre pas de
  second moteur Derby sur la même base.
- `src-test/` : tests JUnit et outil `TestEnvironment`.
