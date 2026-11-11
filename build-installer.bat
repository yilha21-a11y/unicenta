@echo off
REM ==========================================================================
REM  Fabrique dist\OLPOS-Setup.exe : installateur Windows d'OLPOS.
REM
REM  Outils : JDK 17 (jpackage) + Inno Setup 6 (ISCC.exe)
REM           winget install JRSoftware.InnoSetup --scope user
REM
REM  Etapes : 1) build.bat (unicentaopos.jar)
REM           2) jpackage --type app-image -> dist\OLPOS (Java inclus)
REM           3) Inno Setup -> dist\OLPOS-Setup.exe (assistant, raccourcis,
REM              desinstallation, sans droits administrateur)
REM
REM  Le programme installe lit/ecrit ses donnees dans %LOCALAPPDATA%\OLPOS
REM  (-Dolpos.installed=true, voir AppConfig.getInstalledDataDir). Aucun
REM  fichier de test-env\ ni ~\unicentaopos.properties n'est inclus.
REM
REM  Usage : build-installer.bat [version]   (defaut 3.81.1)
REM ==========================================================================
setlocal
set ROOT=%~dp0
set VERSION=%~1
if "%VERSION%"=="" set VERSION=3.81.1
set STAGE=%ROOT%build\installer-input
set DIST=%ROOT%dist

where jpackage >nul 2>nul || (echo [ERREUR] jpackage introuvable : installez un JDK 17 & exit /b 1)
set ISCC=
for %%P in ("%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" "%ProgramFiles%\Inno Setup 6\ISCC.exe") do (
    if exist %%P set ISCC=%%~P
)
if "%ISCC%"=="" (
    echo [ERREUR] Inno Setup 6 introuvable : winget install JRSoftware.InnoSetup --scope user
    exit /b 1
)

echo [1/3] Compilation (build.bat)
call "%ROOT%build.bat" || exit /b 1

echo [2/3] Programme avec Java inclus (jpackage)
if exist "%STAGE%" rmdir /s /q "%STAGE%"
if exist "%DIST%\OLPOS" rmdir /s /q "%DIST%\OLPOS"
if exist "%DIST%\OLPOS-Setup.exe" del /q "%DIST%\OLPOS-Setup.exe"
mkdir "%STAGE%\lib" || exit /b 1

REM Memes bibliotheques que start.bat
copy /y "%ROOT%unicentaopos.jar" "%STAGE%" >nul || exit /b 1
for %%J in (jasperreports-4.5.1 jcommon-1.0.15 jfreechart-1.0.12 jdt-compiler-3.1.1 commons-beanutils-1.8.3 commons-digester-2.1 iText-2.1.7 poi-3.8-20120326 barcode4j-2.0 commons-codec-1.4 velocity-1.7-dep oro-2.0.8 commons-collections-3.2.1 commons-lang-2.4 bsh-core-2.0b4 RXTXcomm jpos1121 swingx-all-1.6.4 substance substance-swingx substance-extras trident swing-layout-1.0.4 AbsoluteLayout javacsv mysql-connector-java-5.1.26-bin axis jaxrpc saaj wsdl4j-1.5.1 commons-discovery-0.4 commons-logging-1.1) do (
    copy /y "%ROOT%lib\%%J.jar" "%STAGE%" >nul || (echo [ERREUR] lib\%%J.jar manquant & exit /b 1)
)
REM Pilote de base : charge via db.driverlib = <programme>\app\lib\derby.jar
copy /y "%ROOT%lib\derby.jar" "%STAGE%\lib" >nul || exit /b 1
copy /y "%ROOT%lib\derbyclient.jar" "%STAGE%\lib" >nul || exit /b 1
REM Les dossiers locales\ et reports\ du classpath sont mis en jar
jar cf "%STAGE%\olpos-locales.jar" -C "%ROOT%locales" . || exit /b 1
jar cf "%STAGE%\olpos-reports.jar" -C "%ROOT%reports" . || exit /b 1
copy /y "%ROOT%olpos_splash.png" "%STAGE%" >nul || exit /b 1
xcopy /e /i /q /y "%ROOT%lib\Windows" "%STAGE%\native\Windows" >nul || exit /b 1

jpackage --type app-image --name OLPOS --dest "%DIST%" ^
  --input "%STAGE%" --main-jar unicentaopos.jar --main-class com.openbravo.pos.forms.StartPOS ^
  --icon "%ROOT%olpos.ico" --app-version %VERSION% --vendor OLPOS ^
  --description "OLPOS - caisse (version derivee d'uniCenta oPOS, licence GNU GPL v3)" ^
  --copyright "Base sur uniCenta oPOS et Openbravo POS - licence GNU GPL v3" ^
  --java-options "-Dolpos.installed=true" ^
  --java-options "-Djava.library.path=$APPDIR\native\Windows\i368-mingw32" ^
  --java-options "-Ddirname.path=$APPDIR\\" ^
  --java-options "-splash:$APPDIR\olpos_splash.png" || exit /b 1

REM Controle : aucune classe manquante pour OLPOS et son apparence (Substance)
set JDCP=
for %%F in ("%DIST%\OLPOS\app\*.jar" "%DIST%\OLPOS\app\lib\*.jar") do call set "JDCP=%%JDCP%%;%%~fF"
jdeps --multi-release 17 --missing-deps -cp "%JDCP:~1%" "%DIST%\OLPOS\app\unicentaopos.jar" "%DIST%\OLPOS\app\substance.jar" "%DIST%\OLPOS\app\swingx-all-1.6.4.jar" > "%ROOT%build\missing-deps.txt" 2>&1
findstr /c:"not found" "%ROOT%build\missing-deps.txt" >nul && (echo [ERREUR] Bibliotheques manquantes dans le programme : & type "%ROOT%build\missing-deps.txt" & exit /b 1)

echo [3/3] Installateur (Inno Setup)
"%ISCC%" /Q /DAppVersion=%VERSION% "%ROOT%installer\olpos.iss" || exit /b 1

echo.
echo Installateur cree : %DIST%\OLPOS-Setup.exe
endlocal
