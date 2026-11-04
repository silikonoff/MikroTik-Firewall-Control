# Native Windows PowerShell. Downloads Gradle 8.9; requires Android SDK 35 and JDK 17+.
param([string]$SdkPath, [string]$JdkPath)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ($SdkPath) { $env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkPath).Path }
if ($JdkPath) { $env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkPath).Path }
if (!$env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.gradle-user-home' }
if (!$env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $PSScriptRoot '.build-tools\android-user' }
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
$version = '8.9'
$cache = Join-Path $PSScriptRoot '.gradle-distributions'
$exe = Join-Path $cache "gradle-$version\bin\gradle.bat"
if (!$env:JAVA_HOME -and !(Get-Command java -ErrorAction SilentlyContinue)) {
    $jbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path (Join-Path $jbr 'bin\java.exe')) { $env:JAVA_HOME = $jbr }
    else { throw 'Install JDK 17+ or configure JAVA_HOME (Android Studio jbr also works).' }
}
if (!$env:ANDROID_HOME -and !$env:ANDROID_SDK_ROOT -and !(Test-Path 'local.properties')) {
    $likely = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $likely) { $env:ANDROID_HOME = $likely }
    else { throw 'Android SDK not found. Install platform android-35 and build-tools via Android Studio; set ANDROID_HOME.' }
}
if (!(Test-Path $exe)) {
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $zip = Join-Path $cache "gradle-$version-bin.zip"
    $url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"
    Write-Host "Downloading Gradle $version from the official distribution service..."
    Invoke-WebRequest -UseBasicParsing $url -OutFile $zip
    Invoke-WebRequest -UseBasicParsing "$url.sha256" -OutFile "$zip.sha256"
    $expected = (Get-Content -Raw "$zip.sha256").Trim()
    if ($expected -notmatch '^[A-Fa-f0-9]{64}$') { throw 'Invalid Gradle checksum response' }
    if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne $expected) { throw 'Gradle SHA256 mismatch' }
    Expand-Archive -Path $zip -DestinationPath $cache -Force
    Remove-Item $zip, "$zip.sha256"
}
$gradleArgs = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', '--no-daemon', '--console=plain')
if (!(Test-Path (Join-Path $env:USERPROFILE '.android\debug.keystore'))) {
    $gradleArgs += "-PdebugKeystore=$(Join-Path $env:ANDROID_USER_HOME 'debug.keystore')"
}
& $exe @gradleArgs
if ($LASTEXITCODE -ne 0) { throw 'Tests, lint or APK build failed. See the Gradle output.' }
foreach ($directory in @('release', 'dist')) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $apk = Join-Path $directory 'MikroTikFirewallControl-v0.2.11-debug.apk'
    Copy-Item 'app\build\outputs\apk\debug\app-debug.apk' $apk -Force
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  MikroTikFirewallControl-v0.2.11-debug.apk" | Set-Content "$apk.sha256" -Encoding ascii
    Write-Host "APK: $PSScriptRoot\$apk SHA256: $hash"
}
