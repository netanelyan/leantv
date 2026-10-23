# Builds launcher\ into dist\LeanTVHome.apk without Gradle (aapt2 + javac + d8 + apksigner).
# Needs: Android SDK (build-tools + a platform) and a JDK 11+.
param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

$bt = Get-ChildItem "$Sdk\build-tools" | Sort-Object { [version]$_.Name } | Select-Object -Last 1
$plat = Get-ChildItem "$Sdk\platforms" | Sort-Object { [int]($_.Name -replace '\D', '') } | Select-Object -Last 1
if (-not $bt -or -not $plat) { throw "Android SDK build-tools/platform not found under $Sdk" }
$androidJar = "$($plat.FullName)\android.jar"
Write-Host "build-tools $($bt.Name), $($plat.Name)"

# Native SDK tools choke on non-ASCII paths (this folder is under a Hebrew-named Desktop), so build in %TEMP%.
$work = Join-Path ([IO.Path]::GetTempPath()) 'leantv-build'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force "$work\gen", "$work\classes", "$work\dex" | Out-Null
Copy-Item -Recurse "$root\launcher\*" $work

function Run($exe, [string[]]$argv) {
    # Windows PowerShell turns native stderr into errors; judge tools by exit code only.
    $ErrorActionPreference = 'Continue'
    & $exe @argv 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^Note: ' }
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed ($LASTEXITCODE)" }
}

Push-Location $work
try {
    Run "$($bt.FullName)\aapt2.exe" @('compile', '--dir', 'res', '-o', 'res.zip')
    Run "$($bt.FullName)\aapt2.exe" @('link', '-I', $androidJar, '--manifest', 'AndroidManifest.xml',
        '--java', 'gen', '-o', 'unsigned.apk', 'res.zip')
    # Not aapt2 -A: on Windows it writes asset entries with backslashes (assets/wallpapers\x.jpg),
    # which Android cannot find. jar writes proper '/' paths; 0 = stored, JPEGs don't compress anyway.
    Run 'jar' @('uf0M', 'unsigned.apk', 'assets')

    $sources = @(Get-ChildItem -Recurse src, gen -Filter *.java | ForEach-Object { $_.FullName })
    Run 'javac' (@('-nowarn', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-bootclasspath', "$androidJar;$($bt.FullName)\core-lambda-stubs.jar", '-d', 'classes') + $sources)

    $classes = @(Get-ChildItem -Recurse classes -Filter *.class | ForEach-Object { $_.FullName })
    Run "$($bt.FullName)\d8.bat" (@('--release', '--min-api', '21', '--lib', $androidJar, '--output', 'dex') + $classes)

    Run 'jar' @('ufM', 'unsigned.apk', '-C', 'dex', 'classes.dex')
    Run "$($bt.FullName)\zipalign.exe" @('-p', '-f', '4', 'unsigned.apk', 'aligned.apk')

    # Stable signing key so future builds install as updates. Kept out of git.
    $ks = "$root\keys\leantv.keystore"
    if (-not (Test-Path $ks)) {
        New-Item -ItemType Directory -Force "$root\keys" | Out-Null
        Run 'keytool' @('-genkeypair', '-keystore', "$work\leantv.keystore", '-alias', 'leantv', '-keyalg', 'RSA',
            '-storetype', 'PKCS12', '-keysize', '2048', '-validity', '10000', '-storepass', 'leantv', '-keypass', 'leantv',
            '-dname', 'CN=LeanTV Home')
        Copy-Item "$work\leantv.keystore" $ks
    } else {
        Copy-Item $ks "$work\leantv.keystore"
    }
    Run "$($bt.FullName)\apksigner.bat" @('sign', '--ks', "$work\leantv.keystore", '--ks-pass', 'pass:leantv',
        '--out', 'LeanTVHome.apk', 'aligned.apk')
    Run "$($bt.FullName)\apksigner.bat" @('verify', 'LeanTVHome.apk')

    New-Item -ItemType Directory -Force "$root\dist" | Out-Null
    Copy-Item LeanTVHome.apk "$root\dist\LeanTVHome.apk" -Force
    $kb = [int]((Get-Item "$root\dist\LeanTVHome.apk").Length / 1KB)
    Write-Host "OK -> dist\LeanTVHome.apk ($kb KB)"
} finally {
    Pop-Location
}
