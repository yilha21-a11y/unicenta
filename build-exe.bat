@echo off
REM ==========================================================================
REM  Fabrique dist\OLPOS-Test\OLPOS-Test.exe (programme Windows avec Java inclus).
REM  Outil : jpackage (fourni avec le JDK 17). Aucun installateur, aucune
REM  installation : le dossier dist\OLPOS-Test se lance tel quel.
REM
REM  SECURITE : l'exe ouvre TOUJOURS test-env\unicentaopos-test.properties
REM  (base de TEST). Il n'utilise jamais ~\unicentaopos.properties.
REM
REM  Prerequis : build.bat (unicentaopos.jar) puis test-env-setup.bat
REM ==========================================================================
setlocal
set DIRNAME=%~dp0
set CONFIG=%DIRNAME%test-env\unicentaopos-test.properties
set STAGE=%DIRNAME%build\exe-input
set DIST=%DIRNAME%dist

if not exist "%DIRNAME%unicentaopos.jar" (
    echo [ERREUR] unicentaopos.jar absent : lancez d'abord build.bat
    exit /b 1
)
if not exist "%CONFIG%" (
    echo [ERREUR] %CONFIG% absent : lancez d'abord test-env-setup.bat
    exit /b 1
)
where jpackage >nul 2>nul || (echo [ERREUR] jpackage introuvable : installez un JDK 17 & exit /b 1)

if exist "%STAGE%" rmdir /s /q "%STAGE%"
if exist "%DIST%\OLPOS-Test" rmdir /s /q "%DIST%\OLPOS-Test"
mkdir "%STAGE%" || exit /b 1

REM Memes bibliotheques que start.bat (derby est charge via db.driverlib de la configuration)
copy /y "%DIRNAME%unicentaopos.jar" "%STAGE%" >nul || exit /b 1
for %%J in (jasperreports-4.5.1 jcommon-1.0.15 jfreechart-1.0.12 jdt-compiler-3.1.1 commons-beanutils-1.8.3 commons-digester-2.1 iText-2.1.7 poi-3.8-20120326 barcode4j-2.0 commons-codec-1.4 velocity-1.7-dep oro-2.0.8 commons-collections-3.2.1 commons-lang-2.4 bsh-core-2.0b4 RXTXcomm jpos1121 swingx-all-1.6.4 substance substance-swingx substance-extras swing-layout-1.0.4 AbsoluteLayout javacsv mysql-connector-java-5.1.26-bin axis jaxrpc saaj wsdl4j-1.5.1 commons-discovery-0.4 commons-logging-1.1) do (
    copy /y "%DIRNAME%lib\%%J.jar" "%STAGE%" >nul || (echo [ERREUR] lib\%%J.jar manquant & exit /b 1)
)
REM Les dossiers locales\ et reports\ du classpath sont mis en jar
jar cf "%STAGE%\olpos-locales.jar" -C "%DIRNAME%locales" . || exit /b 1
jar cf "%STAGE%\olpos-reports.jar" -C "%DIRNAME%reports" . || exit /b 1
copy /y "%DIRNAME%olpos_splash.png" "%STAGE%" >nul
xcopy /e /i /q /y "%DIRNAME%lib\Windows" "%STAGE%\native\Windows" >nul

jpackage --type app-image --name OLPOS-Test --dest "%DIST%" ^
  --input "%STAGE%" --main-jar unicentaopos.jar --main-class com.openbravo.pos.forms.StartPOS ^
  --icon "%DIRNAME%olpos.ico" --app-version 3.81 --vendor OLPOS ^
  --description "OLPOS (version derivee d'uniCenta oPOS, licence GPL) - environnement de TEST" ^
  --java-options "-Djava.library.path=$APPDIR\native\Windows\i368-mingw32" ^
  --java-options "-Ddirname.path=$APPDIR\\" ^
  --java-options "-Dderby.stream.error.file=%DIRNAME%test-env\derby.log" ^
  --java-options "-splash:$APPDIR\olpos_splash.png" ^
  --arguments "%CONFIG%" || exit /b 1

echo.
echo Programme cree : %DIST%\OLPOS-Test\OLPOS-Test.exe
endlocal
