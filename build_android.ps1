$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidRoot = Join-Path $projectRoot "mobile_android"
$signingRoot = Join-Path $androidRoot ".signing"
$propertiesPath = Join-Path $signingRoot "keystore.properties"
$keystorePath = Join-Path $signingRoot "inscreen-release.jks"
$wrapperJar = Join-Path $androidRoot "gradle\wrapper\gradle-wrapper.jar"
$providerPropertiesPath = Join-Path $androidRoot ".provider\provider.properties"

function Find-Java17Home {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += "C:\Program Files\Android\Android Studio\jbr"
    $candidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    $candidates += Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    $candidates += Get-ChildItem (Join-Path $env:LOCALAPPDATA "Programs\Microsoft") -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate "bin\java.exe"
        if (-not (Test-Path $java)) { continue }
        $version = & $java --version | Select-Object -First 1
        if ($version -match '(?:version "|openjdk )(17|18|19|2[0-9])') { return $candidate }
    }
    throw "Se necesita JDK 17 o posterior. Instala Android Studio o un JDK 17 y vuelve a ejecutar este script."
}

function New-RandomSecret {
    $bytes = New-Object byte[] 24
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes).Replace("/", "_").Replace("+", "-").TrimEnd("=")
}

$taskJavaHome = Find-Java17Home
$env:JAVA_HOME = $taskJavaHome
$env:Path = "$(Join-Path $taskJavaHome 'bin');$env:Path"

$taskAndroidSdk = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path $taskAndroidSdk)) {
    throw "No se encontró Android SDK. Instala Android SDK Platform 36 y Build Tools desde Android Studio."
}
$env:ANDROID_SDK_ROOT = $taskAndroidSdk
$escapedSdk = $taskAndroidSdk.Replace("\", "\\").Replace(":", "\:")
Set-Content -Path (Join-Path $androidRoot "local.properties") -Value "sdk.dir=$escapedSdk" -Encoding ASCII

$sdkManager = Get-ChildItem (Join-Path $taskAndroidSdk "cmdline-tools") -Recurse -Filter "sdkmanager.bat" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (-not (Test-Path (Join-Path $taskAndroidSdk "platforms\android-36"))) {
    if (-not $sdkManager) { throw "Falta Android Platform 36 y no se encontró sdkmanager para instalarla." }
    & $sdkManager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
    if ($LASTEXITCODE -ne 0) { throw "No se pudo instalar Android Platform 36." }
}

if (-not (Test-Path $wrapperJar)) {
    throw "Falta gradle-wrapper.jar. Restaura el wrapper del proyecto antes de compilar."
}

if (-not (Test-Path $providerPropertiesPath)) {
    throw "Falta mobile_android\.provider\provider.properties. Copia provider.properties.example y configura baseUrl y token."
}
$providerProperties = Get-Content $providerPropertiesPath -Raw
if ($providerProperties -notmatch '(?m)^baseUrl=https://[^\r\n]+\r?$' -or $providerProperties -notmatch '(?m)^token=[^\r\n]+\r?$') {
    throw "La configuración del proveedor debe incluir baseUrl HTTPS y token."
}

if (-not (Test-Path $propertiesPath)) {
    New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
    $storePassword = New-RandomSecret
    $keyPassword = New-RandomSecret
    $keytool = Join-Path $taskJavaHome "bin\keytool.exe"
    & $keytool -genkeypair -v -storetype JKS -keystore $keystorePath -storepass $storePassword -keypass $keyPassword `
        -alias inscreen -keyalg RSA -keysize 3072 -validity 10000 `
        -dname "CN=InScreen Local, O=InScreen, C=AR"
    if ($LASTEXITCODE -ne 0) { throw "No se pudo crear la clave de firma del APK." }
    @(
        "storeFile=.signing/inscreen-release.jks"
        "storePassword=$storePassword"
        "keyAlias=inscreen"
        "keyPassword=$keyPassword"
    ) | Set-Content -Path $propertiesPath -Encoding ASCII
    Write-Warning "Se creó la firma en mobile_android\.signing. Haz una copia de seguridad para poder actualizar el APK instalado."
}

Push-Location $androidRoot
try {
    & .\gradlew.bat --no-daemon test assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "La compilación Android falló." }
} finally {
    Pop-Location
}

$releaseApk = Join-Path $androidRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $releaseApk)) { throw "Gradle terminó sin producir app-release.apk." }
Copy-Item -Force $releaseApk (Join-Path $projectRoot "mobile\InScreenMic.apk")
Write-Host "APK listo en: .\mobile\InScreenMic.apk"
