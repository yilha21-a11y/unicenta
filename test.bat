@echo off
REM ==========================================================================
REM  Compile les sources de test (src-test) et lance les tests JUnit.
REM  Prerequis : build.bat (classes compilees dans build\classes).
REM  Usage : test.bat            -> compile + lance tous les tests
REM          test.bat compile    -> compile seulement (utilise par test-env-setup.bat)
REM
REM  Les tests creent leurs bases Derby dans build\test-work (jamais ailleurs).
REM ==========================================================================
setlocal enabledelayedexpansion
set ROOT=%~dp0
set OUT=%ROOT%build\test-classes

if not exist "%ROOT%build\classes\com\openbravo\pos\forms\StartPOS.class" (
    echo [ERREUR] build\classes absent : lancez d'abord build.bat
    exit /b 1
)

REM Classpath = classes compilees de la caisse + bibliotheques, SAUF derby*.jar :
REM la caisse charge Derby a part (propriete db.driverlib), comme en production.
set CP=%ROOT%build\classes;%ROOT%locales
for %%F in ("%ROOT%lib\*.jar") do (
    set N=%%~nF
    if /i not "!N:~0,5!"=="derby" set "CP=!CP!;%%~fF"
)

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
dir /s /b "%ROOT%src-test\*.java" > "%ROOT%build\test-sources.txt"
powershell -NoProfile -Command "(Get-Content '%ROOT%build\test-sources.txt') | ForEach-Object { '\"' + ($_ -replace '\\','/') + '\"' } | Set-Content -Encoding ASCII '%ROOT%build\test-sources.txt'"
javac -nowarn -encoding UTF-8 --release 8 -d "%OUT%" -cp "%CP%" @"%ROOT%build\test-sources.txt"
if errorlevel 1 (
    echo [ERREUR] Compilation des tests echouee.
    exit /b 1
)
if /i "%~1"=="compile" exit /b 0

REM Liste des classes de test (*Test.java)
set TESTS=
for /r "%ROOT%src-test" %%F in (*Test.java) do (
    set P=%%~fF
    set P=!P:%ROOT%src-test\=!
    set P=!P:.java=!
    set P=!P:\=.!
    set TESTS=!TESTS! !P!
)
echo Tests :%TESTS%
java -cp "%OUT%;%CP%" -Ddirname.path="%ROOT%." -Dderby.stream.error.file="%ROOT%build\derby-tests.log" org.junit.runner.JUnitCore %TESTS%
exit /b %errorlevel%
