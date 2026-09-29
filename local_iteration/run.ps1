param([int]$Port = 18080, [switch]$StopOnly)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$env:PYTHONUNBUFFERED = '1'
$env:ITERATION_PORT = [string]$Port
$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss_fff'
$RunDir = Join-Path $Root "iteration_logs\$Stamp"
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$env:ITERATION_LOG_DIR = $RunDir.Replace('\', '/')
$ZipPath = Join-Path $Root "ITERATION_001_LOGS_$Stamp.zip"
$ComposeFile = Join-Path $PSScriptRoot 'compose.yml'
$Project = 'heatnet_iter001'
$ExitCode = 0
$Phase = 'precheck'
$ComposeExe = $null
$ComposePrefix = @()
$utf8 = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

function Invoke-Captured {
    param([string]$Exe, [string[]]$CommandArgs, [string]$LogName)
    $log = Join-Path $RunDir $LogName
    ("COMMAND: " + $Exe + ' ' + ($CommandArgs -join ' ')) | Out-File -FilePath $log -Encoding utf8 -Append
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @CommandArgs 2>&1 | ForEach-Object {
            $line = $_.ToString()
            Write-Host $line
            $line | Out-File -FilePath $log -Encoding utf8 -Append
        }
        $script:NativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
}
function Invoke-Compose {
    param([string[]]$CommandArgs, [string]$LogName, [switch]$AllowFailure)
    $a = @($ComposePrefix) + @('-p', $Project, '-f', $ComposeFile) + $CommandArgs
    Invoke-Captured -Exe $ComposeExe -CommandArgs $a -LogName $LogName
    if (($script:NativeExit -ne 0) -and (-not $AllowFailure)) {
        throw "Docker Compose failed in phase '$Phase', code $script:NativeExit. See $LogName."
    }
}
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'docker not found. Install/start Docker Desktop with Linux containers, then rerun.'
    }
    Invoke-Captured -Exe 'docker' -CommandArgs @('version') -LogName 'docker-version.log'
    if ($script:NativeExit -ne 0) { throw 'Docker engine is not ready. Start Docker Desktop and retry.' }
    Invoke-Captured -Exe 'docker' -CommandArgs @('compose','version') -LogName 'compose-version.log'
    if ($script:NativeExit -eq 0) { $ComposeExe='docker'; $ComposePrefix=@('compose') }
    elseif (Get-Command docker-compose -ErrorAction SilentlyContinue) {
        $ComposeExe='docker-compose'
        Invoke-Captured -Exe $ComposeExe -CommandArgs @('version') -LogName 'compose-version.log'
        if ($script:NativeExit -ne 0) { throw 'Docker Compose is unavailable.' }
    } else { throw 'Docker Compose is unavailable.' }
    if ($StopOnly) {
        $Phase='stop'
        Invoke-Compose -CommandArgs @('down') -LogName 'stop.log'
        Write-Host 'Iteration containers stopped. Other projects and volumes were not removed.'
    } else {
        Write-Host 'This run builds/tests the project and creates a log archive. No files are uploaded.'
        Write-Host 'Previous projects on port 8080 are not stopped. This iteration uses 127.0.0.1:18080.'
        if (-not (Test-Path (Join-Path $Root 'pom.xml'))) { throw 'Extract the entire archive first; pom.xml is missing.' }
        # Stop only an earlier run of this iteration so source/JAR/image cannot be mixed.
        Invoke-Compose -CommandArgs @('stop','app') -LogName 'previous-run-stop.log' -AllowFailure
        $Phase='java11_maven_test_package'
        Invoke-Compose -CommandArgs @('run','--rm','--no-deps','java-test') -LogName '01-maven.log'
        $Jar=Join-Path $Root 'target\heat-network-router-0.1.0-SNAPSHOT.jar'
        if (-not (Test-Path $Jar)) { throw 'Maven completed without the expected JAR.' }
        (Get-FileHash -Algorithm SHA256 $Jar | Select-Object Algorithm,Hash) | ConvertTo-Json | Out-File (Join-Path $RunDir 'jar-sha256.json') -Encoding utf8
        $Phase='build_qa'
        Invoke-Compose -CommandArgs @('build','qa') -LogName '02-qa-build.log'
        $Phase='checker_smoke_tests'
        Invoke-Compose -CommandArgs @('run','--rm','--no-deps','qa','smoke') -LogName '03-qa-smoke.log'
        $Phase='build_application'
        Invoke-Compose -CommandArgs @('build','app') -LogName '04-app-build.log'
        $Phase='start_application'
        Invoke-Compose -CommandArgs @('up','-d','--no-deps','app') -LogName '05-app-start.log'
        $Phase='api_cases_and_checker'
        Invoke-Compose -CommandArgs @('run','--rm','--no-deps','qa','live','--service-url','http://app:8080/api/trace') -LogName '06-api-checks.log'
        $Phase='completed'
        Write-Host "Viewer: http://localhost:$Port/viewer/"
        Write-Host 'Completion of this runner is not a claim of 17/17. Read qa-live.json for coverage.'
    }
} catch {
    $ExitCode=1
    ($_ | Out-String) | Out-File (Join-Path $RunDir 'RUNNER_ERROR.txt') -Encoding utf8
    Write-Host ("Stopped in phase: $Phase. " + $_.Exception.Message) -ForegroundColor Red
} finally {
    if ($ComposeExe) {
        try { Invoke-Compose -CommandArgs @('ps','-a') -LogName 'compose-ps.log' -AllowFailure } catch {}
        try { Invoke-Compose -CommandArgs @('logs','--no-color','--timestamps','app') -LogName 'server.log' -AllowFailure } catch {}
        try { Invoke-Compose -CommandArgs @('images') -LogName 'compose-images.log' -AllowFailure } catch {}
    }
    $Reports = Join-Path $Root 'target\surefire-reports'
    if (Test-Path $Reports) {
        try { Copy-Item $Reports (Join-Path $RunDir 'surefire-reports') -Recurse -Force } catch { Write-Host 'Could not copy some Maven reports; see 01-maven.log.' }
    }
    if (Test-Path (Join-Path $PSScriptRoot 'release.json')) {
        try { Copy-Item (Join-Path $PSScriptRoot 'release.json') (Join-Path $RunDir 'release.json') } catch {}
    }
    @{ iteration='ITERATION_001'; phase=$Phase; exit_code=$ExitCode; port=$Port; time=(Get-Date).ToString('o'); powershell=$PSVersionTable.PSVersion.ToString() } |
        ConvertTo-Json | Out-File (Join-Path $RunDir 'run-state.json') -Encoding utf8
    try {
        Compress-Archive -Path (Join-Path $RunDir '*') -DestinationPath $ZipPath -Force
        Write-Host "SEND THIS FILE: $ZipPath" -ForegroundColor Green
    } catch {
        Write-Host "ZIP creation failed. Send the folder instead: $RunDir" -ForegroundColor Yellow
    }
}
exit $ExitCode
