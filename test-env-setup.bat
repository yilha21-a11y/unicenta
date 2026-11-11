@echo off
REM ==========================================================================
REM  Cree l'environnement de TEST dans le dossier test-env\ :
REM    - base Derby neuve (test-env\database), creee par le script officiel
REM    - configuration test-env\unicentaopos-test.properties
REM      (imprimantes = ecran, mode restaurant, API mobile activee)
REM    - donnees de demonstration + un serveur "Serveur Test"
REM
REM  Ne touche JAMAIS a votre installation (~\unicentaopos.properties, ~\.unicenta).
REM
REM  Usage : test-env-setup.bat [--reset] [--port 8095] [--password monmotdepasse] [--timezone America/Los_Angeles]
REM ==========================================================================
setlocal enabledelayedexpansion
set ROOT=%~dp0
call "%ROOT%test.bat" compile || exit /b 1

set CP=%ROOT%build\test-classes;%ROOT%build\classes;%ROOT%locales
for %%F in ("%ROOT%lib\*.jar") do (
    set N=%%~nF
    if /i not "!N:~0,5!"=="derby" set "CP=!CP!;%%~fF"
)
pushd "%ROOT%"
java -cp "%CP%" -Dderby.stream.error.file="%ROOT%test-env\derby.log" com.openbravo.pos.testenv.TestEnvironment "%ROOT%test-env" %*
set RC=%errorlevel%
popd
exit /b %RC%
