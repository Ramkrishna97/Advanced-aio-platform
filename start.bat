@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_ROOT=%~dp0"
set "BACKEND=%PROJECT_ROOT%Backend"
set "UI=%PROJECT_ROOT%UI\play"

where java.exe >nul 2>&1
if errorlevel 1 (
  echo Java was not found. Install Java 17 or newer and try again.
  pause
  exit /b 1
)
where curl.exe >nul 2>&1
if errorlevel 1 (
  echo curl.exe was not found. This launcher requires the Windows curl command.
  pause
  exit /b 1
)
if not exist "%BACKEND%\gradlew.bat" (
  echo Backend folder not found: "%BACKEND%"
  pause
  exit /b 1
)
if not exist "%UI%\package.json" (
  echo React app folder not found: "%UI%"
  pause
  exit /b 1
)
where npm.cmd >nul 2>&1
if errorlevel 1 (
  echo npm.cmd was not found. Install Node.js and try again.
  pause
  exit /b 1
)

echo Restarting OnePlace services with the current build...
call "%PROJECT_ROOT%stop.bat"

if not exist "%PROJECT_ROOT%resources\database" mkdir "%PROJECT_ROOT%resources\database"
if not exist "%PROJECT_ROOT%resources\data" mkdir "%PROJECT_ROOT%resources\data"
for %%S in (auth cart catalog classroom) do (
  if not exist "%PROJECT_ROOT%resources\database\%%S.mv.db" if exist "%BACKEND%\data\%%S.mv.db" copy /Y "%BACKEND%\data\%%S.mv.db" "%PROJECT_ROOT%resources\database\%%S.mv.db" >nul
)

if not exist "%UI%\node_modules" (
  echo Installing React dependencies...
  pushd "%UI%"
  call npm.cmd install
  if errorlevel 1 (
    popd
    echo React dependency installation failed.
    pause
    exit /b 1
  )
  popd
)

curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:3000/" | findstr /C:"OnePlace Dashboard" >nul 2>&1
if errorlevel 1 (
  echo Starting React while backend services build...
  pushd "%UI%"
  start "OnePlace React UI" /min "%ComSpec%" /k "set BROWSER=none && set PORT=3000 && npm.cmd start"
  popd
)

echo Building the backend services...
pushd "%BACKEND%"
call gradlew.bat :gateway:bootJar :auth-service:bootJar :catalog-service:bootJar :cart-service:bootJar :classroom-service:bootJar :discovery-service:bootJar
if errorlevel 1 (
  popd
  echo Backend build failed. Review the Gradle output above.
  pause
  exit /b 1
)
popd

curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:8761/actuator/health" >nul 2>&1
if errorlevel 1 (
  echo Starting discovery-service on port 8761...
  pushd "%BACKEND%"
  start "OnePlace discovery-service" /min java.exe -jar "discovery-service\build\libs\discovery-service-0.1.0.jar"
  popd
)
curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:8084/actuator/health" >nul 2>&1
if errorlevel 1 (
  echo Starting classroom-service on port 8084...
  pushd "%BACKEND%"
  start "OnePlace classroom-service" /min java.exe -jar "classroom-service\build\libs\classroom-service-0.1.0.jar"
  popd
)
curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:8081/actuator/health" >nul 2>&1
if errorlevel 1 (
  echo Starting auth-service on port 8081...
  pushd "%BACKEND%"
  start "OnePlace auth-service" /min java.exe -jar "auth-service\build\libs\auth-service-0.1.0.jar"
  popd
)
curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:8082/actuator/health" >nul 2>&1
if errorlevel 1 (
  echo Starting catalog-service on port 8082...
  pushd "%BACKEND%"
  start "OnePlace catalog-service" /min java.exe -jar "catalog-service\build\libs\catalog-service-0.1.0.jar"
  popd
)
curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:8083/actuator/health" >nul 2>&1
if errorlevel 1 (
  echo Starting cart-service on port 8083...
  pushd "%BACKEND%"
  start "OnePlace cart-service" /min java.exe -jar "cart-service\build\libs\cart-service-0.1.0.jar"
  popd
)
curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:8080/actuator/health" >nul 2>&1
if errorlevel 1 (
  echo Starting gateway on port 8080...
  pushd "%BACKEND%"
  start "OnePlace gateway" /min java.exe -jar "gateway\build\libs\gateway-0.1.0.jar"
  popd
)

echo Waiting for backend health endpoints and the React page...
for /l %%I in (1,1,90) do (
  set "READY=0"
  for %%P in (8080 8081 8082 8083 8084 8761 3000) do (
    set "CHECK_PATH=actuator/health"
    if %%P EQU 3000 set "CHECK_PATH="
    curl.exe --fail --silent --output NUL --max-time 2 "http://127.0.0.1:%%P/!CHECK_PATH!" >nul 2>&1
    if not errorlevel 1 set /a READY+=1
  )
  if !READY! EQU 7 goto :open_dashboard
  if !READY! LSS 6 echo Waiting for backend services: !READY!/6 health endpoints ready
  if !READY! GEQ 6 echo Backend ready; waiting for React dashboard on port 3000
  ping.exe -n 3 127.0.0.1 >nul
)

echo Startup timed out. Check health endpoints at 8080-8084 and 8761, and React at 3000.
echo Backend actuator health is available at each service port; React serves the dashboard on port 3000.
pause
exit /b 1

:open_dashboard
echo All services are ready. The dashboard will refresh its service data in the current page.
exit /b 0

