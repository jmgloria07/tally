@echo off
rem FitLocal launcher (Windows). See README.md for setup.
rem
rem   start.bat                          start LM Studio + model, build jar if missing, run app
rem   start.bat --rebuild                same, but always rebuild first
rem   start.bat --create-user <name>     build jar if missing, run subcommand, exit (no LM Studio)
rem   start.bat --create-sample-db       build jar if missing, run subcommand, exit (no LM Studio)

setlocal EnableExtensions
cd /d "%~dp0"

set "JAR=backend\target\fitlocal.jar"
set "CONFIG_FILE=%~dp0fitlocal.local.cmd"
set "REBUILD="
set "SUBCOMMAND="
set "USING_LOADED="

if /I "%~1"=="--rebuild" set "REBUILD=1"
if /I "%~1"=="--create-user" set "SUBCOMMAND=1"
if /I "%~1"=="--create-sample-db" set "SUBCOMMAND=1"
if "%~1"=="" goto :check_java
if defined REBUILD goto :check_java
if defined SUBCOMMAND goto :check_java
echo Unknown option: %~1
echo.
echo Usage:
echo   start.bat                          start LM Studio + model, build if needed, run FitLocal
echo   start.bat --rebuild                rebuild the app first (use after git pull)
echo   start.bat --create-user ^<name^>     create a user, or reset an existing user's password
echo   start.bat --create-sample-db       regenerate data\fitlocal.db.example
exit /b 1

rem ---------------------------------------------------------------------------------------------
rem 1. Prerequisites: Java 21 or newer.
rem ---------------------------------------------------------------------------------------------
:check_java
where java >nul 2>&1
if errorlevel 1 goto :no_java
set "JAVA_SPEC="
for /f "tokens=2 delims==" %%V in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /C:"java.specification.version"') do set "JAVA_SPEC=%%V"
set "JAVA_SPEC=%JAVA_SPEC: =%"
set "JAVA_MAJOR="
for /f "tokens=1 delims=." %%M in ("%JAVA_SPEC%") do set "JAVA_MAJOR=%%M"
if not defined JAVA_MAJOR goto :no_java
if %JAVA_MAJOR% LSS 21 goto :old_java
goto :check_port

:no_java
echo ERROR: Java was not found on PATH.
echo Install JDK 21, for example:  winget install EclipseAdoptium.Temurin.21.JDK
echo Then open a new terminal and run start.bat again.
exit /b 1

:old_java
echo ERROR: Java %JAVA_SPEC% found, but FitLocal needs Java 21 or newer.
echo Install JDK 21, for example:  winget install EclipseAdoptium.Temurin.21.JDK
exit /b 1

rem ---------------------------------------------------------------------------------------------
rem 2. Refuse to start or rebuild while FitLocal is already running: the running app holds the jar
rem    open, so a rebuild would fail halfway. Subcommands are fine alongside the running app.
rem ---------------------------------------------------------------------------------------------
:check_port
if defined SUBCOMMAND goto :maybe_build
netstat -ano | findstr /R /C:"127\.0\.0\.1:8765 .*LISTENING" >nul
if not errorlevel 1 (
  echo ERROR: Something is already listening on 127.0.0.1:8765.
  echo FitLocal or another app is already running there. Stop it ^(Ctrl+C in its window^),
  echo then run start.bat again.
  exit /b 1
)

rem ---------------------------------------------------------------------------------------------
rem 3. Build when the jar is missing or --rebuild was passed (frontend first, then the jar).
rem ---------------------------------------------------------------------------------------------
:maybe_build
if defined REBUILD goto :build
if not exist "%JAR%" goto :build
goto :after_build

:build
where npm >nul 2>&1
if errorlevel 1 (
  echo ERROR: Node.js/npm is needed to build the app. Install Node.js LTS, then run start.bat again.
  exit /b 1
)
echo Building the web UI...
pushd frontend
call npm ci
if errorlevel 1 goto :build_failed_popd
call npm run build
if errorlevel 1 goto :build_failed_popd
popd
echo Building the app (tests are run separately with: cd backend ^&^& mvnw test)...
pushd backend
call mvnw.cmd -q -DskipTests package
if errorlevel 1 goto :build_failed_popd
popd
echo Build complete.
echo.
goto :after_build

:build_failed_popd
popd
echo ERROR: The build failed. Review the messages above.
exit /b 1

:after_build
rem ---------------------------------------------------------------------------------------------
rem 4. Subcommands run the jar and exit with its exit code. No LM Studio, no server.
rem ---------------------------------------------------------------------------------------------
if not defined SUBCOMMAND goto :lm_studio
rem Kept outside parentheses: a path argument containing ")" would otherwise break the block.
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------------------------------
rem 5. LM Studio (ported from Start-FitLocal-All.bat). The tracker works without it; only the coach
rem    needs it, so problems here are warnings, not errors.
rem ---------------------------------------------------------------------------------------------
:lm_studio
set "LMS_EXE=%USERPROFILE%\.lmstudio\bin\lms.exe"
if not exist "%LMS_EXE%" set "LMS_EXE="
if not defined LMS_EXE (
  for /f "delims=" %%A in ('where lms.exe 2^>nul') do if not defined LMS_EXE set "LMS_EXE=%%A"
)
if not defined LMS_EXE (
  echo WARNING: LM Studio's lms.exe was not found. Starting FitLocal without the AI coach.
  echo Open LM Studio once ^(it installs lms.exe^), then run start.bat again.
  goto :run_app
)

set "LM_STUDIO_EXE="
if exist "%LOCALAPPDATA%\Programs\LM Studio\LM Studio.exe" set "LM_STUDIO_EXE=%LOCALAPPDATA%\Programs\LM Studio\LM Studio.exe"
if not defined LM_STUDIO_EXE if exist "%ProgramFiles%\LM Studio\LM Studio.exe" set "LM_STUDIO_EXE=%ProgramFiles%\LM Studio\LM Studio.exe"
if not defined LM_STUDIO_EXE if exist "%LOCALAPPDATA%\LM Studio\LM Studio.exe" set "LM_STUDIO_EXE=%LOCALAPPDATA%\LM Studio\LM Studio.exe"

if defined LM_STUDIO_EXE (
  tasklist /FI "IMAGENAME eq LM Studio.exe" 2>nul | find /I "LM Studio.exe" >nul
  if errorlevel 1 (
    echo Starting LM Studio...
    start "" "%LM_STUDIO_EXE%"
    timeout /t 12 /nobreak >nul
  ) else (
    echo LM Studio is already running.
  )
) else (
  echo WARNING: Could not find the LM Studio desktop app. Continuing with the lms CLI only.
)

rem If a chat model is already loaded (for example from the LM Studio app), use it instead of loading a
rem second one: two models rarely fit in VRAM together. FitLocal asks LM Studio which model is loaded on
rem every coach question, so swapping models in LM Studio later also works without restarting.
call :detect_loaded
if not defined LOADED_MODEL goto :load_configured_model
set "USING_LOADED=1"
if not defined LOADED_CONTEXT (
  echo Using the model already loaded in LM Studio: %LOADED_MODEL% ^(context length unknown^).
  goto :check_api
)
echo Using the model already loaded in LM Studio: %LOADED_MODEL% ^(context %LOADED_CONTEXT% tokens^).
if %LOADED_CONTEXT% LSS 16384 (
  echo WARNING: The coach needs a context of about 20480 tokens; long questions may fail with %LOADED_CONTEXT%.
  echo Reload the model in LM Studio with a larger context length, or unload it and run start.bat again.
)
goto :check_api

:load_configured_model
rem Nothing is loaded: load the model saved in fitlocal.local.cmd (gitignored), asked for on first run.
if exist "%CONFIG_FILE%" call "%CONFIG_FILE%"
if defined MODEL_KEY goto :model_configured

echo.
echo Models installed in LM Studio:
echo ------------------------------------------------------------
"%LMS_EXE%" ls
echo ------------------------------------------------------------
echo Copy the exact model key shown above for the coach model (for example gpt-oss-20B or Qwen3 14B).
set /p "MODEL_KEY=Model key: "
if not defined MODEL_KEY (
  echo WARNING: No model key entered. Starting FitLocal without the AI coach.
  goto :run_app
)
>"%CONFIG_FILE%" echo @echo off
>>"%CONFIG_FILE%" echo rem FitLocal local launcher settings. Written by start.bat; safe to edit.
>>"%CONFIG_FILE%" echo set "MODEL_KEY=%MODEL_KEY%"
>>"%CONFIG_FILE%" echo set "CONTEXT_LENGTH=20480"
echo Saved the model key to fitlocal.local.cmd.
echo.

:model_configured
if not defined CONTEXT_LENGTH set "CONTEXT_LENGTH=20480"
rem No custom --identifier: the model keeps its own key as its name, exactly like a model loaded from the
rem LM Studio app, so LM Studio's model list and FitLocal's requests always agree on what it is called.
echo Loading %MODEL_KEY% ^(context %CONTEXT_LENGTH% tokens, GPU offload max^).
echo This can take a while after boot; please wait.
"%LMS_EXE%" load "%MODEL_KEY%" --context-length %CONTEXT_LENGTH% --gpu max
if errorlevel 1 echo WARNING: LM Studio reported a problem while loading the model.

:check_api
rem Start the API only if it is not already responding. Keep it localhost-only.
curl.exe -fsS --max-time 2 http://127.0.0.1:1234/v1/models >nul 2>&1
if not errorlevel 1 goto :api_ready
echo Starting the LM Studio API on 127.0.0.1:1234...
start "LM Studio API" /min "%LMS_EXE%" server start --port 1234 --bind 127.0.0.1
echo Waiting for the LM Studio API ^(up to 80 seconds^)...
for /L %%I in (1,1,40) do (
  curl.exe -fsS --max-time 2 http://127.0.0.1:1234/v1/models >nul 2>&1 && goto :api_ready
  timeout /t 2 /nobreak >nul
)
echo WARNING: The LM Studio API did not respond. FitLocal will start; the coach will show as offline.
goto :run_app

:api_ready
echo LM Studio API is ready.
if defined USING_LOADED goto :run_app
rem Point FitLocal at the model just loaded, by the name LM Studio reports for it. It stays preferred
rem while it is loaded; if another model is loaded instead later, FitLocal uses that one.
call :detect_loaded
if defined LOADED_MODEL (
  set "LM_STUDIO_MODEL=%LOADED_MODEL%"
  echo FitLocal will use %LOADED_MODEL%.
) else (
  echo WARNING: %MODEL_KEY% did not load. The coach will use whichever model is loaded in LM Studio, if any.
)

rem ---------------------------------------------------------------------------------------------
rem 6. Run the app in this window. Ctrl+C stops it.
rem ---------------------------------------------------------------------------------------------
:run_app
echo.
echo ============================================================
echo  FitLocal: http://127.0.0.1:8765  (this PC)
echo  Other devices: use your Tailscale HTTPS URL (tailscale serve).
echo  The app only listens on 127.0.0.1. Press Ctrl+C to stop.
echo ============================================================
echo.
java -jar "%JAR%"
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------------------------------
rem Subroutine (call :detect_loaded): the first loaded chat model (type llm or vlm) in "lms ps --json".
rem Sets LOADED_MODEL to its identifier and LOADED_CONTEXT to its context length; both stay empty
rem when none is loaded or lms output can't be read.
rem ---------------------------------------------------------------------------------------------
:detect_loaded
set "LOADED_MODEL="
set "LOADED_CONTEXT="
for /f "tokens=1,2 delims=|" %%A in ('powershell -NoProfile -Command "try { $m = & $env:LMS_EXE ps --json 2>$null | ConvertFrom-Json } catch { $m = @() }; $l = @($m | Where-Object { $_.type -eq 'llm' -or $_.type -eq 'vlm' })[0]; if ($l) { '{0}|{1}' -f $l.identifier, $l.contextLength }"') do (
  set "LOADED_MODEL=%%A"
  set "LOADED_CONTEXT=%%B"
)
exit /b 0
