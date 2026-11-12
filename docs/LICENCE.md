# Activer et renouveler une licence OLPOS

## Ce que contient une licence

Numéro de série (ex. `OLP-7K2M-…`), titulaire (client) et restaurant, date de
début, date de fin, fonctions autorisées (`ventes`, `mobile` = téléphones,
`cuisine` = envoi cuisine), nombre de postes (0 = sans limite) et, en option,
le code du poste autorisé.

**Dates** : comparées en UTC. La licence commence au **début** de la date de
début (00:00 UTC) et se termine à la **fin** de la date de fin
(23:59:59 UTC, date incluse). L'écran l'affiche en heure locale (à Vancouver,
UTC-8, « jusqu'au 11/11/2027 » se termine le 11/11/2027 à 15:59).

## Activer

1. Écran de connexion → bouton **Activation / Licence** (aussi proposé au
   démarrage tant qu'OLPOS n'est pas activé).
2. Collez le **code d'activation** (il commence par `OLPOS1.`), puis
   **Activer ce code** ; ou **Importer un fichier de licence** (`.olposlic`).
3. L'écran affiche le numéro, le titulaire, la date d'expiration et les jours
   restants.

Pour une licence liée à un poste : **Copier le code de ce poste** et envoyez-le
à votre fournisseur. Ce code est une empreinte (non réversible) de
l'identifiant d'installation de Windows : aucun nom, aucune adresse, aucun
numéro de matériel.

## Renouveler

Importez la nouvelle licence de la même façon : elle remplace l'ancienne,
**sans réinstallation ni perte de données**. Une licence plus ancienne que
celle installée est refusée.

Avertissements au démarrage : **30, 7 et 1 jour(s)** avant l'expiration.

## Licence absente, invalide ou expirée

- Les **nouvelles ventes** sont bloquées (caisse et téléphones) ;
- une commande déjà commencée peut être terminée et **encaissée** ;
  aucun paiement en cours n'est interrompu ;
- l'activation, la consultation, les rapports, les sauvegardes et l'export
  restent accessibles ;
- **aucune donnée n'est supprimée ni chiffrée**.

## Contrôles et limites (activation hors ligne)

- Signature **Ed25519** : une licence modifiée ou fabriquée est refusée. OLPOS
  ne contient que la clé **publique** ; la clé privée reste dans OLPOS License
  Manager.
- **Recul d'horloge** : OLPOS garde la dernière heure connue (base et dossier
  de données) ; si l'horloge du PC est reculée (au-delà de 10 minutes), les
  nouvelles ventes sont bloquées jusqu'à correction.
- **Postes** : chaque caisse partageant la même base s'enregistre ; au-delà du
  nombre autorisé, le poste en trop ne peut pas vendre. Un poste inactif
  30 jours libère sa place ; bouton **Postes…** pour libérer un PC remplacé.
- **Remplacement de PC** (licence liée) : demandez un transfert ; votre
  fournisseur émet une nouvelle révision pour le code du nouveau poste.

Limites honnêtes : sans serveur de licences, une licence déjà délivrée **ne
peut pas être révoquée** avant sa date de fin, le nombre de postes n'est
contrôlé qu'entre caisses partageant la **même base**, et effacer les traces
locales permet de contourner la détection du recul d'horloge. Une révocation
immédiate ou un contrôle global des postes demanderait une vérification en
ligne, non implémentée. OLPOS est un logiciel libre (GPL v3) : ce mécanisme ne
retire aucun droit de modifier ou redistribuer le code source.
