@echo off
REM ==========================================================================
REM  Fabrique les DEUX installateurs Windows (Java inclus) :
REM
REM    dist\OLPOS5-Setup.exe                         installateur CLIENT (restaurant)
REM    dist-admin\OLPOS-License-Manager-Setup.exe    installateur ADMINISTRATEUR
REM                                                  (ne jamais publier ni installer
REM                                                   chez le restaurant)
REM
REM  Outils : JDK 17 (jpackage, jdeps), Maven 3.9, Inno Setup 6 (ISCC.exe)
REM           winget install JRSoftware.InnoSetup --scope user
REM
REM  Usage : build-installers.bat [version-olpos] [version-gestionnaire] [-skiptests]
REM          (defaut 5.0.1 et 1.0.0)
REM
REM  La cle PUBLIQUE de verification des licences doit etre dans
REM  src\main\resources\com\olpos\license\trusted-keys.properties
REM  (exportee depuis OLPOS License Manager). Aucune cle privee n'est utilisee
REM  ni copiee ici.
REM ==========================================================================
setlocal EnableDelayedExpansion
set ROOT=%~dp0
set VERSION=%~1
if "%VERSION%"=="" set VERSION=5.0.1
set LMVERSION=%~2
if "%LMVERSION%"=="" set LMVERSION=1.0.0
set MVNFLAGS=
if /i "%~3"=="-skiptests" set MVNFLAGS=-DskipTests
set STAGE=%ROOT%build\installer-input
set LMSTAGE=%ROOT%build\license-manager-input
set DIST=%ROOT%dist
set DISTADMIN=%ROOT%dist-admin

where jpackage >nul 2>nul || (echo [ERREUR] jpackage introuvable : installez un JDK 17 & exit /b 1)
set MVN=mvn
where mvn >nul 2>nul || set MVN=%USERPROFILE%\tools\apache-maven-3.9.9\bin\mvn.cmd
set ISCC=
for %%P in ("%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" "%ProgramFiles%\Inno Setup 6\ISCC.exe") do (
    if exist %%P set ISCC=%%~P
)
if "%ISCC%"=="" (
    echo [ERREUR] Inno Setup 6 introuvable : winget install JRSoftware.InnoSetup --scope user
    exit /b 1
)
if not exist "%ROOT%src\main\resources\com\olpos\license\trusted-keys.properties" (
    echo [ERREUR] Cle publique des licences absente : exportez-la depuis OLPOS License Manager
    echo          vers src\main\resources\com\olpos\license\trusted-keys.properties
    exit /b 1
)
findstr /i /c:"PRIVATE" "%ROOT%src\main\resources\com\olpos\license\trusted-keys.properties" >nul && (
    echo [ERREUR] trusted-keys.properties semble contenir une cle PRIVEE : refus.
    exit /b 1
)

echo [1/6] Compilation et tests d'OLPOS (Maven)
call "%MVN%" -B -q -f "%ROOT%pom.xml" clean package %MVNFLAGS% || exit /b 1

echo [2/6] Programme OLPOS avec Java inclus (jpackage)
if exist "%STAGE%" rmdir /s /q "%STAGE%"
if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%STAGE%\lib" || exit /b 1
copy /y "%ROOT%target\unicentaopos.jar" "%STAGE%" >nul || exit /b 1
copy /y "%ROOT%target\lib\*.jar" "%STAGE%\lib" >nul || exit /b 1
REM bibliotheques natives Windows (ports serie / paralleles) seulement
xcopy /e /i /q /y "%ROOT%target\lib\Windows" "%STAGE%\lib\Windows" >nul || exit /b 1
copy /y "%ROOT%src\main\resources\com\unicenta\images\uniCenta_splash_dark.png" "%STAGE%\olpos_splash.png" >nul || exit /b 1

REM jlink sans --strip-native-commands : keytool.exe reste disponible pour OLPOS-HTTPS.cmd
jpackage --type app-image --name OLPOS5 --dest "%DIST%" ^
  --input "%STAGE%" --main-jar unicentaopos.jar --main-class com.unicenta.pos.forms.StartPOS ^
  --icon "%ROOT%branding\source\olpos.ico" --app-version %VERSION% --vendor OLPOS ^
  --description "OLPOS - caisse (version derivee d'uniCenta oPOS 5.0, licence GNU GPL v3)" ^
  --copyright "Base sur uniCenta oPOS et Openbravo POS - licence GNU GPL v3" ^
  --jlink-options "--strip-debug --no-man-pages --no-header-files" ^
  --java-options "-Djava.library.path=$APPDIR\lib\Windows\i368-mingw32" ^
  --java-options "--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED --add-opens=java.desktop/javax.swing=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED" ^
  --java-options "--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED --add-opens=java.desktop/sun.swing=ALL-UNNAMED" ^
  --java-options "-Ddirname.path=$APPDIR\\" ^
  --java-options "-splash:$APPDIR\olpos_splash.png" || exit /b 1
if not exist "%DIST%\OLPOS5\runtime\bin\keytool.exe" (echo [ERREUR] keytool absent du Java inclus & exit /b 1)

echo [3/6] Controle des bibliotheques (jdeps)
REM Aucune classe manquante pour OLPOS ni pour son apparence (Substance / Trident)
jdeps --multi-release 17 --missing-deps -cp "%DIST%\OLPOS5\app\lib\*" "%DIST%\OLPOS5\app\unicentaopos.jar" "%DIST%\OLPOS5\app\lib\substance-7.1.00.jar" > "%ROOT%build\missing-deps.txt" 2>&1 || (echo [ERREUR] jdeps & exit /b 1)
findstr /c:"not found" "%ROOT%build\missing-deps.txt" >nul && (echo [ERREUR] classes manquantes, voir build\missing-deps.txt & exit /b 1)

echo [4/6] Installateur client OLPOS (Inno Setup)
"%ISCC%" /Q /DAppVersion=%VERSION% "%ROOT%installer\olpos.iss" || exit /b 1

echo [5/6] OLPOS License Manager (Maven + jpackage)
call "%MVN%" -B -q -f "%ROOT%license-manager\pom.xml" clean package %MVNFLAGS% || exit /b 1
if exist "%LMSTAGE%" rmdir /s /q "%LMSTAGE%"
if exist "%DISTADMIN%" rmdir /s /q "%DISTADMIN%"
mkdir "%LMSTAGE%" || exit /b 1
copy /y "%ROOT%license-manager\target\olpos-license-manager.jar" "%LMSTAGE%" >nul || exit /b 1
jpackage --type app-image --name "OLPOS License Manager" --dest "%DISTADMIN%" ^
  --input "%LMSTAGE%" --main-jar olpos-license-manager.jar --main-class com.olpos.licensemanager.ManagerApp ^
  --icon "%ROOT%license-manager\installer\olpos-license-manager.ico" --app-version %LMVERSION% --vendor OLPOS ^
  --description "OLPOS License Manager - outil administrateur des licences OLPOS" ^
  --copyright "OLPOS - licence GNU GPL v3" || exit /b 1

echo [6/6] Installateur administrateur (Inno Setup)
"%ISCC%" /Q /DAppVersion=%LMVERSION% "%ROOT%license-manager\installer\license-manager.iss" || exit /b 1

echo.
echo Installateur CLIENT         : %DIST%\OLPOS5-Setup.exe
echo Installateur ADMINISTRATEUR : %DISTADMIN%\OLPOS-License-Manager-Setup.exe  (ne pas publier)
endlocal
