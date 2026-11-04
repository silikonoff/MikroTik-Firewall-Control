# Native Windows: requires JDK and kotlinc in PATH, no Android SDK.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$out = Join-Path ([IO.Path]::GetTempPath()) ("mikrotik-tests-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
try {
    $src = 'app/src/main/java/ru/nikmadz/mikrotikcontrol'
    & kotlinc "$src/core/Models.kt" "$src/core/RouterFirewallService.kt" `
        "$src/net/RouterBackend.kt" "$src/net/HttpRestTransport.kt" `
        'tests/shared/CoreChecks.kt' 'tests/DomainTest.kt' '-include-runtime' '-d' "$out/tests.jar"
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed' }
    & java '-jar' "$out/tests.jar"
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
} finally { Remove-Item -Recurse -Force $out }
