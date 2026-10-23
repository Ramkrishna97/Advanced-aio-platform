@echo off
setlocal EnableExtensions
set "PROJECT_ROOT=%~dp0"
set "PORTS=3000 8080 8081 8082 8083 8084 8085 8086 8087 8088 8761"
echo Stopping OnePlace services...
for %%P in (%PORTS%) do (
  for /f "tokens=5" %%A in ('netstat -ano ^| findstr /R /C:":%%P .*LISTENING"') do (
    if not "%%A"=="0" taskkill /PID %%A /T /F >nul 2>&1
  )
)
echo OnePlace services stopped.
endlocal
