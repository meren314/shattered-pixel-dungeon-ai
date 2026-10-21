param([switch]$ForceBuild)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$base = 'http://127.0.0.1:18765'
try { $null = Invoke-RestMethod "$base/observe" -TimeoutSec 3 }
catch {
    $installRoot = Join-Path $root 'desktop\build\install\desktop'
    $entry = Join-Path $installRoot 'bin\desktop.bat'
    $libDir = Join-Path $installRoot 'lib'
    $installedFiles = @()
    if (Test-Path -LiteralPath $libDir) {
        $installedFiles = @(Get-ChildItem -LiteralPath $libDir -File -Filter '*.jar')
    }

    $inputFiles = @()
    foreach ($path in @(
        (Join-Path $root 'build.gradle'),
        (Join-Path $root 'settings.gradle'),
        (Join-Path $root 'gradle.properties'),
        (Join-Path $root 'core\build.gradle'),
        (Join-Path $root 'desktop\build.gradle'),
        (Join-Path $root 'ai-probe\build.gradle')
    )) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $inputFiles += Get-Item -LiteralPath $path
        }
    }
    foreach ($relative in @('core\src','desktop\src','ai-probe\src','SPD-classes\src')) {
        $sourceDir = Join-Path $root $relative
        if (Test-Path -LiteralPath $sourceDir -PathType Container) {
            $inputFiles += Get-ChildItem -LiteralPath $sourceDir -Recurse -File
        }
    }

    $needsBuild = $ForceBuild -or !(Test-Path -LiteralPath $entry) -or $installedFiles.Count -eq 0
    if (!$needsBuild -and $inputFiles.Count -gt 0) {
        $latestInput = ($inputFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
        $latestInstall = ($installedFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
        $needsBuild = $latestInput -gt $latestInstall
    }

    if ($needsBuild) {
        Write-Host '安装构建缺失、过期或指定强制重建；正在运行 Gradle installDist。'
        Push-Location $root
        try {
            & .\gradlew.bat :desktop:installDist --console=plain
            if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
        } finally { Pop-Location }
    } else {
        Write-Host '复用最新的安装构建，跳过 Gradle 编译。'
    }

    $javaw = (Get-Command javaw.exe -ErrorAction Stop).Source
    $libs = Join-Path $root 'desktop\build\install\desktop\lib\*'
    $launchArgs = @('"-DSpecification-Title=SPD AI Probe"', '-DImplementation-Title=com.shatteredpixel.shatteredpixeldungeon', '-DSpecification-Version=4.0.2', '-DImplementation-Version=922', '-Dspd.ai.probe=true', '-cp', ('"'+$libs+'"'), 'com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher')
    Start-Process -FilePath $javaw -ArgumentList $launchArgs -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot 'game.log') -RedirectStandardError (Join-Path $PSScriptRoot 'game-error.log')
    $connected = $false
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Milliseconds 500
        try { $null = Invoke-RestMethod "$base/observe" -TimeoutSec 2; $connected=$true; break } catch {}
    }
    if (!$connected) { throw 'Game did not start. See ai-probe\game-error.log.' }
}
Start-Process "$base/"
Write-Host 'AI probe is running at http://127.0.0.1:18765/'
