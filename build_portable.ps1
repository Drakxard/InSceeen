param(
    [switch]$SkipAndroidBuild
)

$ErrorActionPreference = "Stop"

if (-not $SkipAndroidBuild) {
    & .\build_android.ps1
    if ($LASTEXITCODE -ne 0) { throw "No se pudo compilar el APK Android." }
} elseif (-not (Test-Path ".\mobile\InScreenMic.apk")) {
    throw "No existe mobile\InScreenMic.apk. Ejecuta build_android.ps1 primero."
}

if (-not (Test-Path ".\.venv\Scripts\python.exe")) {
    python -m venv .venv
}

.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt

.\.venv\Scripts\python.exe -m PyInstaller `
    --noconfirm `
    --clean `
    --name InScreen `
    --windowed `
    --icon ".\assets\inscreen.xpm" `
    --add-data ".\mobile\InScreenMic.apk;mobile" `
    --add-data ".\web;web" `
    --add-data ".\assets;assets" `
    --collect-all aiohttp `
    --hidden-import pynput.keyboard._win32 `
    --hidden-import winrt.windows.media.control `
    --hidden-import winrt.windows.foundation `
    --hidden-import winrt.windows.foundation.collections `
    --hidden-import winrt.windows.storage `
    --hidden-import winrt.windows.storage.streams `
    app.py

if (Test-Path ".\.env") {
    Copy-Item -Force ".\.env" ".\dist\InScreen\.env"
}

if (Test-Path ".\config.json") {
    Copy-Item -Force ".\config.json" ".\dist\InScreen\config.json"
}

if (Test-Path ".\config.example.json") {
    Copy-Item -Force ".\config.example.json" ".\dist\InScreen\config.example.json"
}

Write-Host "Portable listo en: .\dist\InScreen\InScreen.exe"
Write-Host "Para moverlo, copia la carpeta completa: .\dist\InScreen"
Write-Host "Los certificados y el token persistentes se guardan en %LOCALAPPDATA%\InScreen\runtime."
Write-Host "El APK Android queda incluido para descarga manual; el QR aparece solo si el celular lo solicita."
