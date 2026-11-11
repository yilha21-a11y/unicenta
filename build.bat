@echo off
REM ==========================================================================
REM  uniCenta oPOS - compilation sans Ant ni NetBeans (JDK 8 ou plus requis)
REM
REM  Produit : unicentaopos.jar a la racine du projet (utilise par start.bat
REM            et start-test.bat).
REM  Usage   : build.bat            -> compile + cree unicentaopos.jar
REM            build.bat classes    -> compile seulement (build\classes), par
REM                                    exemple quand la caisse est ouverte et
REM                                    que le jar ne doit pas etre remplace
REM ==========================================================================
setlocal
set ROOT=%~dp0
set OUT=%ROOT%build\classes

where javac >nul 2>nul
if errorlevel 1 (
    echo [ERREUR] javac introuvable. Installez un JDK 8+ et ajoutez-le au PATH.
    exit /b 1
)

echo [1/4] Nettoyage de build\classes
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo [2/4] Compilation des sources (src-beans, src-data, src-pos)
dir /s /b "%ROOT%src-beans\*.java" "%ROOT%src-data\*.java" "%ROOT%src-pos\*.java" > "%ROOT%build\sources.txt"
REM Les chemins Windows contiennent des "\" : javac les accepte dans un fichier @ s'ils sont entre guillemets doubles
powershell -NoProfile -Command "(Get-Content '%ROOT%build\sources.txt') | ForEach-Object { '\"' + ($_ -replace '\\','/') + '\"' } | Set-Content -Encoding ASCII '%ROOT%build\sources.txt'"
javac -nowarn -encoding UTF-8 --release 8 -d "%OUT%" -cp "%ROOT%lib\*" @"%ROOT%build\sources.txt"
if errorlevel 1 (
    echo [ERREUR] La compilation a echoue.
    exit /b 1
)

echo [3/4] Copie des ressources (images, scripts SQL, modeles, traductions)
for %%D in (src-beans src-data src-pos) do (
    robocopy "%ROOT%%%D" "%OUT%" /E /XF *.java *.form .nbattrs Thumbs.db /NFL /NDL /NJH /NJS /NP >nul
    if errorlevel 8 (
        echo [ERREUR] Copie des ressources de %%D impossible.
        exit /b 1
    )
)

if /i "%~1"=="classes" (
    echo OK : classes compilees dans build\classes ^(jar non regenere^)
    exit /b 0
)

echo [4/4] Creation de unicentaopos.jar
jar cfe "%ROOT%unicentaopos.jar" com.openbravo.pos.forms.StartPOS -C "%OUT%" .
if errorlevel 1 (
    echo [ERREUR] Creation du jar impossible.
    exit /b 1
)

echo.
echo OK : %ROOT%unicentaopos.jar
endlocal
exit /b 0
