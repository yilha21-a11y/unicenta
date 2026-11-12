# OLPOS 5 : migration vers uniCenta oPOS 5.0

Branche `feature/olpos5-migration`, créée depuis `upstream/unicenta-5.0`
(code communautaire uniCenta 5.0 non modifié). Fonctions reprises de
`feature/olpos-installer` (qui contient aussi `feature/olpos-order-numbers`).
Les branches de référence et `master` ne sont pas modifiées.

## Décisions techniques

| Sujet | Décision |
|---|---|
| Compilation | Maven 3.9 + JDK 17. Lombok 1.18.6 → 1.18.30 (plantait sous Java 17). |
| Java 17 à l'exécution | WebLaF/XStream (uniCenta 5.0) exigent des `--add-opens` : ajoutés au lanceur. |
| Dépendances retirées | `unicenta-plugins` (binaire sans source ni licence, envoyait nom du PC et version à `api.unicenta.io`), `charm-glisten` (commercial, inutilisé), `uk.co.pos_apps:openbravo` (introuvable). Tableau de bord en ligne, PaymentSense et reçu numérique uniCenta : message « non disponible ». |
| Paquets | `com.openbravo` → `com.unicenta` ; nouvelles classes OLPOS dans les paquets 5.0 (`api`, `license`, `sales.restaurant`…). |
| Isolement | Données dans `-Dolpos.datadir` ou `%LOCALAPPDATA%\OLPOS5` ; jamais `~/unicentaopos.properties` ni `~/.unicenta` (valeurs par défaut d'uniCenta 5.0 = base de production possible). Toutes les relectures de configuration passent par le fichier de l'instance. Instance unique sur le port RMI 10995 (127.0.0.1), plus 1099 (uniCenta). |
| Base de données | Schéma 5.0 inchangé (version, verrou `LOCKED`, couverts, `printto`). Modèles OLPOS (`Printer.TicketKitchen`, `Printer.TicketBill`) ajoutés aux bases qui ne les ont pas ; numéro de commande et marque insérés sans rien supprimer. |
| Cuisine | 5.0 : champ « Imprimante » du produit (0 = pas de cuisine, 1-6 = imprimante). Permission 5.0 `sales.PrintRemote` (vue `sales.PrintKitchen` par le téléphone). Le script `script.SendOrder` n'est plus utilisé en mode restaurant. |
| Identité de commande | Identifiant du ticket (UUID) + version, vérifiés à chaque écriture. |
| Licences | Ed25519 (Java), code commun `license-core/`, clé publique seule dans OLPOS, dates UTC (fin incluse). |
| Version | OLPOS 5.0.1 ; version de schéma (`AppLocal.APP_VERSION`) laissée à 5.0. Produit distinct d'OLPOS 3.81 (autre AppId, autre dossier). |

## Tableau de migration

État : **T** = terminé et testé automatiquement ; **TI** = testé aussi dans le programme installé ; **R** = réalisé, à tester sur place ; **B** = bloqué / non réalisé.

| Fonction | Fichiers d'origine (3.81) | Adaptation 5.0 | Tests | État |
|---|---|---|---|---|
| Connexion employés (mot de passe, permissions) | `api/ApiServer`, `ApiSessions`, `MobileDataService` | droits relus à chaque requête (2 s) | `ApiIntegrationTest`, `ApiSessionsTest` | TI (login refusé/accepté) |
| Catalogue, catégories, prix | `MobileDataService` | `getProductInfo` 5.0, montants formatés par la caisse | `ApiIntegrationTest` | T |
| Salles et tables | `MobileDataService` | tables 5.0 (largeur/hauteur) | `ApiIntegrationTest` | T |
| Création / modification de commandes, quantités, notes | `MobileOrderService` | `baseOrderId` obligatoire | `ApiOrdersIntegrationTest` | T |
| Prix et taxes côté serveur | `MobileOrderService` | `TaxesLogic` 5.0 | `ApiIntegrationTest`, `ApiOrdersIntegrationTest` | T |
| Page web téléphone | `api/web/*` | devise de la configuration, identifiant de commande, 5xx gardé, stockage indisponible signalé | syntaxe JS + API | R (non testé sur iPhone) |
| Accès mobile à l'installation | `installer/olpos.iss` | `OLPOS5`, case « téléphone » | installation silencieuse | TI |
| Modifications simultanées caisse / téléphones | `TableLocks`, `TableTicketStore`, `JTicketsBagRestaurantMap` | plan de salle 5.0 conservé (LOCKED, Override, couverts) | `TableTicketStoreTest`, `TableLocksTest`, `ApiOrdersIntegrationTest` | T (écran caisse : R) |
| Identifiants de requête, doublons, redémarrage | `ProcessedRequests`, `MobileOrderService` | — | `ApiOrdersIntegrationTest` | T |
| Demande incertaine conservée (coupure, 500) | `app.js` | 5xx = incertain | — | R |
| Identifiant + version de commande | `TableTicketStore` | **nouveau** contrôle d'identifiant | `ancienneDemande…` (commande, cuisine) | T |
| Encaissement : contrôle avant paiement | `JTicketsBagRestaurantMap` | **nouveau** `checkBeforePayment` | — (écran) | R |
| Imprimante réseau ESC/POS TCP, port configurable | `PrinterWritterSocket`, `DeviceTicket` | choix « socket » à l'écran | `PrinterWritterSocketTest` | T (papier : R) |
| Nouveaux ajouts seulement, AJOUT / DUPLICATA | `Printer.TicketKitchen.xml` | nouveau modèle multi-imprimantes 5.0 | `ApiKitchenIntegrationTest` | T |
| Même service caisse / téléphones | `MobileKitchenService` | `KitchenService` commun, bouton de la caisse | tests API ; écran caisse | R (bouton caisse) |
| Statuts envoyé / confirmé / échec / à vérifier | `PrintRunner`, `TillPrinter` | **échec certain** ; ESC/POS : résultat réel de l'envoi ; pilote Windows : erreur remontée | `ApiKitchenIntegrationTest` | T (matériel : R) |
| Pas de réimpression automatique | `MobileKitchenService` | — | `ApiKitchenIntegrationTest` | T |
| Addition provisoire depuis le téléphone | `MobileBillService`, `Printer.TicketBill.xml` | `orderId` | `ApiBillIntegrationTest` | T |
| Encaissement à la caisse | — | inchangé | — | R |
| Numéro de commande 1-100, partagé, atomique | `OrderNumbers` | — | `OrderNumberIntegrationTest` (99, 100, 1, 2 ; simultanés ; redémarrage) | T |
| Même numéro caisse / téléphone / tickets ; conservé | `OrderNumberTemplates`, `JPanelTicket` | blocs insérés dans les modèles 5.0 | `OrderNumberIntegrationTest` | T |
| Date, heure, table ou « À emporter » | modèles | — | `OrderNumberIntegrationTest` | T |
| Configuration par instance, données séparées | `AppConfig` | dossier `OLPOS5`, tous les accès 5.0 corrigés | `AppConfigDataDirTest`, `AppConfigActiveTest` | TI |
| Devise de la configuration | `app.js` | plus de « € » codé en dur | — | R |
| Conservation à la mise à jour / désinstallation | `olpos.iss` | — | désinstallation + réinstallation | TI |
| HTTPS | — | **nouveau** : PKCS12, autorité locale, `OLPOS-HTTPS.cmd` | `ApiHttpsTest` ; script exécuté | T (téléphone réel : R) |
| Identité OLPOS | `Branding`, images | images 5.0 remplacées, À propos, titres, page téléphone, installateurs | `BrandingTest` ; capture du programme installé | TI |
| Licences (activation) | — | **nouveau** `LicenseService`, écran Activation | `LicenseServiceTest`, `ApiOrdersIntegrationTest` | TI |
| OLPOS License Manager | — | **nouveau** programme séparé | `ManagerStoreTest` ; installation | TI (écrans : R) |
| Migration des données OLPOS 3.81 → 5 | — | non réalisée (schémas différents) | — | B |
