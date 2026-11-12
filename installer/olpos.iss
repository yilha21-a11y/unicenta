; ==========================================================================
;  OLPOS 5 - installateur CLIENT (restaurant), Inno Setup 6
;  Construit par build-installers.bat -> dist\OLPOS5-Setup.exe
;
;  - Installation par utilisateur, sans droits administrateur :
;      programme : %LOCALAPPDATA%\Programs\OLPOS5   (remplace a chaque mise a jour)
;      donnees   : %LOCALAPPDATA%\OLPOS5            (olpos.properties, base Derby,
;                                                   journaux, licence, certificat HTTPS)
;    Les donnees ne sont jamais supprimees par une mise a jour ni par la
;    desinstallation.
;  - Java (runtime jpackage) et toutes les bibliotheques sont inclus.
;  - Ne contient AUCUNE cle privee, aucun outil de creation de licences
;    (OLPOS License Manager a son propre installateur administrateur).
;  - OLPOS est une version derivee d'uniCenta oPOS 5.0, licence GNU GPL v3.
;  - Distinct d'OLPOS 3.81 (autre identifiant, autre dossier de donnees) :
;    une installation OLPOS 3.81 ou uniCenta existante n'est pas modifiee.
; ==========================================================================

#ifndef AppVersion
  #define AppVersion "5.0.1"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\OLPOS5"
#endif

[Setup]
; Identifiant fixe d'OLPOS 5 : NE PAS CHANGER (sert a reconnaitre les mises a jour)
AppId={{9B2E5C41-7D3A-4F06-B8E1-5A0C2D7F3E91}
AppName=OLPOS 5
AppVersion={#AppVersion}
AppVerName=OLPOS {#AppVersion}
AppPublisher=OLPOS
AppComments=Caisse OLPOS (version derivee d'uniCenta oPOS 5.0, licence GNU GPL v3)
AppCopyright=Base sur uniCenta oPOS et Openbravo POS (Openbravo S.L.) - licence GNU GPL v3
VersionInfoVersion={#AppVersion}
VersionInfoProductName=OLPOS
VersionInfoDescription=Installateur OLPOS

PrivilegesRequired=lowest
DefaultDirName={autopf}\OLPOS5
DefaultGroupName=OLPOS
UsePreviousAppDir=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0

; Assistant : Bienvenue -> Options -> Pret a installer -> Termine
DisableWelcomePage=no
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=no
DisableFinishedPage=no

SetupIconFile=..\branding\source\olpos.ico
UninstallDisplayIcon={app}\OLPOS5.exe
UninstallDisplayName=OLPOS 5
WizardStyle=modern
ShowLanguageDialog=no
LanguageDetectionMethod=none
WizardImageFile=wizard.bmp,wizard-2x.bmp
WizardSmallImageFile=wizard-small.bmp,wizard-small-2x.bmp

; Fermer OLPOS s'il est ouvert pendant une mise a jour
CloseApplications=yes
RestartApplications=no

OutputDir=..\dist
OutputBaseFilename=OLPOS5-Setup
Compression=lzma2/max
SolidCompression=yes

[Languages]
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Messages]
fr.WelcomeLabel2=Cet assistant va installer [name/ver] sur votre ordinateur.%n%nJava est inclus : rien d'autre à installer, aucun droit administrateur requis.%n%nVos données (réglages, base, licence) sont rangées à part, dans %LOCALAPPDATA%\OLPOS5 : elles sont conservées lors des mises à jour et de la désinstallation.%n%nUne installation uniCenta ou OLPOS 3.81 existante n'est pas modifiée.
en.WelcomeLabel2=This will install [name/ver] on your computer.%n%nJava is included; no administrator rights are needed.%n%nYour data (settings, database, license) is stored separately in %LOCALAPPDATA%\OLPOS5 and is kept across updates and uninstall.

[InstallDelete]
; Mise a jour propre : on retire l'ancien programme (jamais les donnees)
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Dirs]
; Dossier des donnees : cree a l'installation, jamais supprime
Name: "{localappdata}\OLPOS5"; Flags: uninsneveruninstall

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\LICENSE.md"; DestDir: "{app}\licences"; DestName: "LICENCE-GPL-3.0.txt"; Flags: ignoreversion
Source: "..\src\other\license\license.txt"; DestDir: "{app}\licences"; DestName: "uniCenta-licence.txt"; Flags: ignoreversion
Source: "..\licences\*"; DestDir: "{app}\licences"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "LISEZMOI.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "OLPOS-HTTPS.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\docs\*.md"; DestDir: "{app}\docs"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\OLPOS"; Filename: "{app}\OLPOS5.exe"; WorkingDir: "{localappdata}\OLPOS5"; IconFilename: "{app}\OLPOS5.exe"; Comment: "Caisse OLPOS"
Name: "{autoprograms}\OLPOS - Guides"; Filename: "{app}\docs"
Name: "{autoprograms}\OLPOS - Licences"; Filename: "{app}\licences"
Name: "{autodesktop}\OLPOS"; Filename: "{app}\OLPOS5.exe"; WorkingDir: "{localappdata}\OLPOS5"; IconFilename: "{app}\OLPOS5.exe"; Comment: "Caisse OLPOS"

[Run]
Filename: "{app}\OLPOS5.exe"; WorkingDir: "{localappdata}\OLPOS5"; Description: "{cm:LaunchProgram,OLPOS}"; Flags: nowait postinstall skipifsilent

[Tasks]
; Cochee par defaut ; une mise a jour reprend le choix precedent
Name: "mobile"; Description: "Activer la prise de commande sur téléphone (page http://<adresse du PC>:8095/)"; GroupDescription: "Options :"

[Code]
{ Ecrit api.enabled=true/false dans %LOCALAPPDATA%\OLPOS5\olpos.properties.
  Les autres reglages du fichier sont conserves. Sans fichier et case
  cochee : rien a ecrire (OLPOS active le mobile par defaut). }
procedure SetMobileEnabled(Enabled: Boolean);
var
  FileName, Value: String;
  Lines, Kept: TArrayOfString;
  I, N: Integer;
begin
  FileName := ExpandConstant('{localappdata}\OLPOS5\olpos.properties');
  if Enabled then Value := 'true' else Value := 'false';
  N := 0;
  if FileExists(FileName) then
  begin
    if LoadStringsFromFile(FileName, Lines) then
      for I := 0 to GetArrayLength(Lines) - 1 do
        if Pos('api.enabled', Trim(Lines[I])) <> 1 then
        begin
          SetArrayLength(Kept, N + 1);
          Kept[N] := Lines[I];
          N := N + 1;
        end;
  end
  else if Enabled then
    Exit;
  SetArrayLength(Kept, N + 1);
  Kept[N] := 'api.enabled=' + Value;
  if not SaveStringsToFile(FileName, Kept, False) then
    Log('Impossible d''ecrire ' + FileName);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    SetMobileEnabled(WizardIsTaskSelected('mobile'));
end;
