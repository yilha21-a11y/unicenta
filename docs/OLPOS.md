# OLPOS — identité et compatibilité

**OLPOS** est une version dérivée d'**uniCenta oPOS 3.81** (Copyright © 2009-2014
uniCenta, lui-même issu d'Openbravo POS), distribuée sous la même licence
**GNU GPL v3**. Les mentions de copyright, licences et attributions d'origine
sont conservées (en-têtes des fichiers, dossier `licensing/`, `LICENSE.md`,
texte « À propos » de l'écran de connexion).

## Ce qui affiche OLPOS

| Élément | Où |
|---|---|
| Titres des fenêtres (caisse, configuration, outils) | `AppLocal.APP_NAME = "OLPOS"` |
| Écran de connexion / « À propos » | `Branding.aboutHtml()` + logo `olpos_logo.png` (`JRootApp`) |
| Barre de titre de la caisse | ressource `Window.Title` (« OLPOS - Point de vente tactile ») + petit logo |
| Icône des fenêtres (16 → 256 px) | `olpos_icon_*.png`, `favicon.png` (`JRootFrame`, `JRootKiosk`, `JFrmConfig`) |
| Écran de démarrage | `olpos_splash.png` (`start.bat`, `start-test.bat`, `start_digital_receipt.bat`) |
| Outil de configuration de la base | `olpos_logo_config.png`, lien « uniCenta (projet d'origine) » |
| Afficheur client, impressions Windows | nom `OLPOS` |
| Tickets (logiciel uniquement) | « OLPOS » + « d'apres uniCenta oPOS - GPL v3 » |
| Messages et traductions | « uniCenta oPOS » → « OLPOS » (hors commentaires de licence) |
| API (`/api/v1/health`) et page téléphone | `"app": "OLPOS"`, logo, icônes iPhone/Android, manifeste, couleurs |
| Installeur | `olpos_installer.xml` (raccourci « OLPOS », `olpos.ico`) |

Couleurs (mesurées sur les images fournies) : bleu marine **#051C4A**, turquoise **#00C2A5**.

Le **nom et le logo du restaurant** sur les tickets ne sont pas modifiés.

## Ce qui garde le nom d'origine (volontairement)

| Élément | Raison |
|---|---|
| Paquets Java `com.openbravo.*`, classes | technique : compatibilité du code et des tickets enregistrés (objets sérialisés) |
| `AppLocal.APP_ID = "unicentaopos"` | identifiant de version dans la base (table `APPLICATIONS`) |
| `unicentaopos.properties`, `unicentaopos-database` | emplacement des configurations et bases existantes |
| `unicentaopos.jar`, `shortName` de l'installeur | scripts et installations existantes |
| En-têtes de licence, `licensing/`, `LICENSE.md`, `README_First.txt`, notes de version | attribution aux auteurs d'origine |
| « uniCenta web site », liens `unicenta.com` | renvoient à la documentation du projet d'origine |
| Anciennes images `unicenta*.png`, `uniCenta_splash_dark.png`, anciens installeurs `unicentaopos_3.*` | historique ; plus utilisés par OLPOS |

## Bases existantes : migration douce

Le titre et les modèles de tickets sont stockés dans la base. Au démarrage,
`BrandingMigration` remplace **uniquement** les lignes exactes livrées par
uniCenta (`>uniCenta oPOS</text>`, la ligne de copyright du logiciel et le titre
par défaut) dans `Window.Title`, `Printer.Ticket`, `Printer.TicketNew`,
`Printer.ReprintTicket`, `Printer.TicketPreview`, `Printer.Start`. Tout le reste
(restaurant, mise en page personnalisée) est inchangé. Désactivable avec
`olpos.branding.migrate=false` dans la configuration.

## Régénérer les images

Sources : `branding/source/olpos-logo.png` (logo horizontal, transparent) et
`branding/source/olpos-icon.png` (symbole). Puis :

```bat
python branding\generate_assets.py
build.bat
```

Le script détoure le symbole (fond transparent, écran central clair pour rester
lisible sur fond sombre), produit toutes les tailles PNG, l'icône Windows
`olpos.ico` (16 à 256 px), l'écran de démarrage, les images de l'installeur et
les icônes de la page téléphone (iPhone : `apple-touch-icon.png` opaque).
