@echo off
rem ==========================================================================
rem  OLPOS - creation du certificat HTTPS de la caisse (une fois par PC)
rem
rem  Cree, dans %LOCALAPPDATA%\OLPOS5\https :
rem    olpos-ca.cer        autorite de certification LOCALE du restaurant
rem                        (certificat public a installer sur chaque telephone)
rem    olpos-https.p12     certificat du PC (adresses IP + nom du PC), 825 jours
rem    password.txt        mot de passe aleatoire de ces fichiers
rem    empreinte.txt       empreinte SHA-256 de l'autorite (a comparer sur le telephone)
rem    ca.p12              cle privee de l'autorite locale : NE PAS COPIER NI PARTAGER
rem  puis active api.https.enabled=true dans olpos.properties.
rem
rem  Aucun certificat n'est desactive ou contourne : le telephone verifie le
rem  certificat du PC grace a l'autorite locale installee. Voir docs\HTTPS.md.
rem ==========================================================================
setlocal EnableExtensions EnableDelayedExpansion

set "KT=%~dp0runtime\bin\keytool.exe"
if not exist "%KT%" (
    for %%k in (keytool.exe) do set "KT=%%~$PATH:k"
)
if not exist "%KT%" (
    echo keytool introuvable : lancez ce script depuis le dossier d'OLPOS.
    exit /b 1
)

set "DATA=%LOCALAPPDATA%\OLPOS5"
if not "%OLPOS_DATADIR%"=="" set "DATA=%OLPOS_DATADIR%"
set "DIR=%DATA%\https"

if exist "%DIR%\olpos-https.p12" (
    echo Un certificat existe deja dans %DIR%.
    choice /M "Le remplacer (les telephones devront reinstaller l'autorite)"
    if errorlevel 2 exit /b 0
    del /q "%DIR%\*.p12" "%DIR%\*.cer" "%DIR%\*.csr" "%DIR%\password.txt" "%DIR%\empreinte.txt" 2>nul
)
if not exist "%DIR%" mkdir "%DIR%"

rem ---- adresses IPv4 du PC (hors boucle locale) et nom du PC
set "SAN=dns:localhost,ip:127.0.0.1,dns:%COMPUTERNAME%"
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }).IPAddress"`) do (
    set "SAN=!SAN!,ip:%%i"
    echo Adresse du PC incluse : %%i
)

rem ---- mot de passe aleatoire (generateur cryptographique de Windows)
for /f "usebackq delims=" %%p in (`powershell -NoProfile -Command "$b = New-Object byte[] 24; (New-Object System.Security.Cryptography.RNGCryptoServiceProvider).GetBytes($b); [Convert]::ToBase64String($b) -replace '[+/=]', 'x'"`) do set "PW=%%p"
if "%PW%"=="" (
    echo Generation du mot de passe impossible.
    exit /b 1
)
> "%DIR%\password.txt" echo %PW%

pushd "%DIR%"
"%KT%" -genkeypair -alias olpos-ca -keyalg EC -groupname secp256r1 -validity 3650 ^
  -dname "CN=OLPOS - autorite locale %COMPUTERNAME%, O=OLPOS" ^
  -ext bc:c=ca:true,pathlen:0 -ext ku:c=keyCertSign,cRLSign ^
  -keystore ca.p12 -storetype PKCS12 -storepass "%PW%" || goto :error
"%KT%" -exportcert -alias olpos-ca -keystore ca.p12 -storepass "%PW%" -rfc -file olpos-ca.cer || goto :error

"%KT%" -genkeypair -alias olpos -keyalg EC -groupname secp256r1 -validity 825 ^
  -dname "CN=%COMPUTERNAME%, O=OLPOS" ^
  -keystore olpos-https.p12 -storetype PKCS12 -storepass "%PW%" || goto :error
"%KT%" -certreq -alias olpos -keystore olpos-https.p12 -storepass "%PW%" -file server.csr || goto :error
"%KT%" -gencert -alias olpos-ca -keystore ca.p12 -storepass "%PW%" -infile server.csr -outfile server.cer ^
  -validity 825 -rfc -ext "san=%SAN%" -ext eku=serverAuth -ext ku:c=digitalSignature || goto :error
"%KT%" -importcert -alias olpos-ca -keystore olpos-https.p12 -storepass "%PW%" -file olpos-ca.cer -noprompt || goto :error
"%KT%" -importcert -alias olpos -keystore olpos-https.p12 -storepass "%PW%" -file server.cer -noprompt || goto :error
del /q server.csr server.cer 2>nul

"%KT%" -printcert -file olpos-ca.cer | findstr /C:"SHA256:" > empreinte.txt
popd

rem ---- activation dans la configuration (la derniere valeur du fichier l'emporte)
>> "%DATA%\olpos.properties" echo api.https.enabled=true

echo.
echo Certificat HTTPS cree dans %DIR%
type "%DIR%\empreinte.txt"
echo.
echo Redemarrez OLPOS. Sur chaque telephone :
echo   1. ouvrez http://^<adresse du PC^>:8094/ et installez le certificat ;
echo   2. iPhone : Reglages ^> General ^> Informations ^> Reglages des certificats : activez OLPOS ;
echo   3. ouvrez ensuite https://^<adresse du PC^>:8095/
echo Pare-feu (administrateur) : netsh advfirewall firewall add rule name="OLPOS certificat" dir=in action=allow protocol=TCP localport=8094 profile=private
exit /b 0

:error
popd
echo ERREUR pendant la creation du certificat.
exit /b 1
