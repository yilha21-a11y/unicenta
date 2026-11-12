# OLPOS 5

Caisse tactile pour restaurants : **version dérivée d'uniCenta oPOS 5.0**
(elle-même dérivée d'Openbravo POS), licence **GNU GPL v3** (`LICENSE.md`).
OLPOS n'est ni produit ni soutenu par uniCenta.

- Prise de commande sur téléphone (navigateur, Wi-Fi du restaurant), HTTPS possible
- Envoi en cuisine fiable (même service pour la caisse et les téléphones)
- Numéro de commande 1 à 100 commun aux tickets et aux bons cuisine
- Activation par licence signée ; outil administrateur séparé : OLPOS License Manager

## Guides

| Guide | |
|---|---|
| `docs/INSTALLATION.md` | installer et configurer OLPOS |
| `docs/TELEPHONE.md` | connecter un téléphone |
| `docs/HTTPS.md` | chiffrer les échanges avec les téléphones |
| `docs/IMPRIMANTE-CUISINE.md` | configurer et tester une imprimante cuisine |
| `docs/LICENCE.md` | activer et renouveler une licence |
| `license-manager/GUIDE.md` | utiliser et sauvegarder OLPOS License Manager (administrateur) |
| `MIGRATION-OLPOS5.md` | portage vers uniCenta 5.0 : décisions et tableau de migration |

## Construire

Prérequis : JDK 17, Maven 3.9, Inno Setup 6.

```
mvn test                                  # tests d'OLPOS (base Derby de test, jamais ~/.unicenta)
mvn -f license-manager/pom.xml test       # tests du gestionnaire de licences
build-installers.bat 5.0.1 1.0.0          # dist\OLPOS5-Setup.exe et dist-admin\OLPOS-License-Manager-Setup.exe
```

La clé **publique** de vérification des licences est dans
`src/main/resources/com/olpos/license/trusted-keys.properties` (exportée depuis
OLPOS License Manager). Aucune clé privée n'est dans ce dépôt ni dans les
installateurs.

## Origine

Code d'origine : uniCenta oPOS 5.0 community
(<https://sourceforge.net/projects/unicentaopos/files/source>), importé tel quel
dans la branche `upstream/unicenta-5.0`.
