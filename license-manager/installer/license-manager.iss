; ==========================================================================
;  OLPOS License Manager - installateur ADMINISTRATEUR (Inno Setup 6)
;  Construit par build-installers.bat -> dist-admin\OLPOS-License-Manager-Setup.exe
;
;  A installer UNIQUEMENT sur le PC de l'administrateur des licences.
;  NE JAMAIS l'installer chez un restaurant ni le publier avec OLPOS.
;
;  - Aucune cle n'est incluse : la cle privee est creee a la premiere
;    ouverture, chiffree par le mot de passe du gestionnaire, dans
;    %APPDATA%\OLPOS License Manager (conserve a la desinstallation).
;  - Licence GNU GPL v3.
; ==========================================================================

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\dist-admin\OLPOS License Manager"
#endif

[Setup]
AppId={{4C8D1F6A-2B7E-4E93-9A15-6F0B3C8D2E74}
AppName=OLPOS License Manager
AppVersion={#AppVersion}
AppVerName=OLPOS License Manager {#AppVersion}
AppPublisher=OLPOS
AppComments=Outil administrateur de creation des licences OLPOS (ne pas installer chez le restaurant)
VersionInfoVersion={#AppVersion}
VersionInfoProductName=OLPOS License Manager
VersionInfoDescription=Installateur OLPOS License Manager

PrivilegesRequired=lowest
DefaultDirName={autopf}\OLPOS License Manager
DefaultGroupName=OLPOS License Manager
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
DisableWelcomePage=no
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=no
SetupIconFile=olpos-license-manager.ico
UninstallDisplayIcon={app}\OLPOS License Manager.exe
UninstallDisplayName=OLPOS License Manager
WizardStyle=modern
ShowLanguageDialog=no
LanguageDetectionMethod=none
WizardImageFile=wizard.bmp,wizard-2x.bmp
WizardSmallImageFile=wizard-small.bmp,wizard-small-2x.bmp
CloseApplications=yes
OutputDir=..\..\dist-admin
OutputBaseFilename=OLPOS-License-Manager-Setup
Compression=lzma2/max
SolidCompression=yes

[Languages]
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"

[Messages]
fr.WelcomeLabel2=Cet assistant va installer [name/ver], l'outil ADMINISTRATEUR qui crée et renouvelle les licences OLPOS.%n%nN'installez PAS ce programme chez un restaurant.%n%nAucune clé n'est fournie : la clé de signature sera créée à la première ouverture et protégée par votre mot de passe. Pensez à la sauvegarder (onglet Sécurité).

[Dirs]
Name: "{userappdata}\OLPOS License Manager"; Flags: uninsneveruninstall

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\LICENSE.md"; DestDir: "{app}\licences"; DestName: "LICENCE-GPL-3.0.txt"; Flags: ignoreversion
Source: "..\GUIDE.md"; DestDir: "{app}"; DestName: "GUIDE.md"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\OLPOS License Manager"; Filename: "{app}\OLPOS License Manager.exe"; Comment: "Licences OLPOS (administrateur)"
Name: "{autoprograms}\OLPOS License Manager - Guide"; Filename: "{app}\GUIDE.md"
Name: "{autodesktop}\OLPOS License Manager"; Filename: "{app}\OLPOS License Manager.exe"

[Run]
Filename: "{app}\OLPOS License Manager.exe"; Description: "{cm:LaunchProgram,OLPOS License Manager}"; Flags: nowait postinstall skipifsilent
