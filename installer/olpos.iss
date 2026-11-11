; ==========================================================================
;  OLPOS - installateur Windows (Inno Setup 6)
;  Construit par build-installer.bat -> dist\OLPOS-Setup.exe
;
;  - Installation par utilisateur, sans droits administrateur :
;      programme  : %LOCALAPPDATA%\Programs\OLPOS   (remplace a chaque mise a jour)
;      donnees    : %LOCALAPPDATA%\OLPOS            (configuration olpos.properties,
;                                                    base Derby, derby.log)
;    Les donnees ne sont jamais supprimees par une mise a jour ni par la
;    desinstallation (Inno Setup ne supprime que ce qu'il a installe).
;  - Java (runtime jpackage) et toutes les bibliotheques sont inclus.
;  - OLPOS est une version derivee d'uniCenta oPOS, sous licence GNU GPL v3.
; ==========================================================================

#ifndef AppVersion
  #define AppVersion "3.81.1"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\OLPOS"
#endif

[Setup]
; Identifiant fixe : NE PAS CHANGER (sert a reconnaitre les mises a jour)
AppId={{6F1C2B7E-4A3D-4E5B-9C8A-0D1E2F3A4B5C}
AppName=OLPOS
AppVersion={#AppVersion}
AppVerName=OLPOS {#AppVersion}
AppPublisher=OLPOS
AppComments=Caisse OLPOS (version derivee d'uniCenta oPOS, licence GNU GPL v3)
AppCopyright=Base sur uniCenta oPOS et Openbravo POS (Openbravo S.L.) - licence GNU GPL v3
VersionInfoVersion={#AppVersion}
VersionInfoProductName=OLPOS
VersionInfoDescription=Installateur OLPOS

PrivilegesRequired=lowest
DefaultDirName={autopf}\OLPOS
DefaultGroupName=OLPOS
UsePreviousAppDir=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0

; Assistant : Bienvenue (Suivant) -> Pret a installer (Installer) -> Termine (Terminer)
DisableWelcomePage=no
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=no
DisableFinishedPage=no

SetupIconFile=..\olpos.ico
UninstallDisplayIcon={app}\OLPOS.exe
UninstallDisplayName=OLPOS
WizardStyle=modern
; Pas de fenetre de choix de langue : francais d'office
ShowLanguageDialog=no
LanguageDetectionMethod=none
WizardImageFile=wizard.bmp,wizard-2x.bmp
WizardSmallImageFile=wizard-small.bmp,wizard-small-2x.bmp

; Fermer OLPOS s'il est ouvert pendant une mise a jour
CloseApplications=yes
RestartApplications=no

OutputDir=..\dist
OutputBaseFilename=OLPOS-Setup
Compression=lzma2/max
SolidCompression=yes

[Languages]
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Messages]
fr.WelcomeLabel2=Cet assistant va installer [name/ver] sur votre ordinateur.%n%nJava est inclus : rien d'autre à installer, aucun droit administrateur requis.%n%nVos données (réglages et base) sont rangées à part, dans %LOCALAPPDATA%\OLPOS : elles sont conservées lors des mises à jour et de la désinstallation.
en.WelcomeLabel2=This will install [name/ver] on your computer.%n%nJava is included; no administrator rights are needed.%n%nYour data (settings and database) is stored separately in %LOCALAPPDATA%\OLPOS and is kept across updates and uninstall.

[InstallDelete]
; Mise a jour propre : on retire l'ancien programme (jamais les donnees)
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Dirs]
; Dossier des donnees : cree a l'installation, jamais supprime
Name: "{localappdata}\OLPOS"; Flags: uninsneveruninstall

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\LICENSE.md"; DestDir: "{app}\licences"; DestName: "LICENCE-GPL-3.0.txt"; Flags: ignoreversion
Source: "..\licensing\*"; DestDir: "{app}\licences\bibliotheques"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "LISEZMOI.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\OLPOS"; Filename: "{app}\OLPOS.exe"; WorkingDir: "{localappdata}\OLPOS"; IconFilename: "{app}\OLPOS.exe"; Comment: "Caisse OLPOS"
Name: "{autoprograms}\OLPOS - Licences"; Filename: "{app}\licences"
Name: "{autodesktop}\OLPOS"; Filename: "{app}\OLPOS.exe"; WorkingDir: "{localappdata}\OLPOS"; IconFilename: "{app}\OLPOS.exe"; Comment: "Caisse OLPOS"

[Run]
Filename: "{app}\OLPOS.exe"; WorkingDir: "{localappdata}\OLPOS"; Description: "{cm:LaunchProgram,OLPOS}"; Flags: nowait postinstall skipifsilent
