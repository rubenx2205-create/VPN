@echo off
REM Doble clic para instalar en modo rapido con notificaciones al movil.
REM Para otras opciones: instalar.bat -Token TOKEN -Dominio vpn.midominio.com
set ARGS=%*
if "%ARGS%"=="" set ARGS=-Notificar
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0vpn-servidor.ps1" instalar %ARGS%
pause
