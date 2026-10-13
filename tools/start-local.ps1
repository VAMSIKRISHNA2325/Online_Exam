param(
    [ValidateSet('demo', 'default')]
    [string]$Profile = 'demo',
    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $localDirectory = Join-Path $projectRoot '.local'
    New-Item -ItemType Directory -Path $localDirectory -Force | Out-Null
    $statePath = Join-Path $localDirectory 'local-app.json'
    $appUrl = "http://127.0.0.1:$Port"

    # Repeated starts return only the process this launcher recorded.
    if (Test-Path -LiteralPath $statePath) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $existingProcess = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
        if ($existingProcess -and
            $existingProcess.StartTime.ToUniversalTime().Ticks.ToString() -eq $state.startedUtcTicks) {
            if ($state.port -ne $Port -or $state.profile -ne $Profile -or $Build) {
                throw 'The local app is running. Stop it with tools/stop-local.ps1 before changing its settings or rebuilding.'
            }
            $ready = Invoke-RestMethod -Uri "$appUrl/api/ready" -TimeoutSec 5
            if ($ready.status -ne 'UP') { throw 'The recorded app is running but its database is not ready.' }
            Write-Output "Local application is already ready at $appUrl/ (PID $($state.pid))."
            return
        }
    }

    $portProbe = New-Object System.Net.Sockets.TcpClient
    try {
        try { $portProbe.Connect('127.0.0.1', $Port) } catch [System.Net.Sockets.SocketException] {
            if ($_.Exception.SocketErrorCode -ne [System.Net.Sockets.SocketError]::ConnectionRefused) { throw }
        }
        if ($portProbe.Connected) { throw "Port $Port is already occupied by another process. Select a different -Port." }
    } finally { $portProbe.Dispose() }

    if (-not (Test-Path -LiteralPath '.env')) {
        throw 'Create a private .env using .env.example and set both MySQL passwords first.'
    }
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath '.env') {
        if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
    }
    foreach ($key in @('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace($settings[$key]) -or $settings[$key].StartsWith('replace-with-')) {
            throw "Set a private $key in .env before starting."
        }
    }
    $mysqlPort = 3307
    if ($settings['MYSQL_PORT']) { $mysqlPort = [int]$settings['MYSQL_PORT'] }

    if (-not $env:JAVA_HOME) {
        $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($javaCommand) {
            $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
        } else {
            $portableJdk = Get-ChildItem -LiteralPath (Join-Path $localDirectory 'jdk') -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
                Select-Object -First 1
            if ($portableJdk) { $env:JAVA_HOME = $portableJdk.FullName }
        }
    }
    if (-not $env:JAVA_HOME) { throw 'Install a Java 21 JDK and set JAVA_HOME.' }
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $java)) { throw 'Install a Java 21 JDK and set JAVA_HOME.' }
    # PowerShell 5.1 treats native stderr as an error record. Java writes its version there.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& $java -version 2>&1 | Out-String)
    $javaVersionExit = $LASTEXITCODE
    $ErrorActionPreference = $savedPreference
    if ($javaVersionExit -ne 0 -or $javaVersion -notmatch 'version "21[.\"]') {
        throw 'This project requires Java 21. Set JAVA_HOME to a Java 21 JDK.'
    }

    $env:DB_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/online_exam?connectionTimeZone=UTC"
    $env:DB_USER = 'online_exam'
    $env:DB_PASSWORD = $settings['MYSQL_PASSWORD']
    $env:PORT = $Port.ToString()
    $env:COOKIE_SECURE = 'false'
    $env:DEBUG = 'false'
    if ($Profile -eq 'demo') {
        $passwordPath = Join-Path $localDirectory 'demo-password.txt'
        if (-not (Test-Path -LiteralPath $passwordPath)) {
            $randomBytes = New-Object byte[] 24
            $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
            try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
            [IO.File]::WriteAllText($passwordPath, [Convert]::ToBase64String($randomBytes))
        }
        $env:DEMO_PASSWORD = (Get-Content -LiteralPath $passwordPath -Raw).Trim()
        if ($env:DEMO_PASSWORD.Length -lt 12 -or $env:DEMO_PASSWORD.Length -gt 128) {
            throw 'The private .local/demo-password.txt must contain 12 to 128 characters.'
        }
    }

    $jar = Join-Path $projectRoot 'target/online-exam-0.1.0-SNAPSHOT.jar'
    if ($Build -or -not (Test-Path -LiteralPath $jar)) {
        $env:MAVEN_USER_HOME = Join-Path $localDirectory 'wrapper'
        $ErrorActionPreference = 'Continue'
        & .\mvnw.cmd -B -ntp '-Dmaven.repo.local=.local/m2' clean package
        $buildExit = $LASTEXITCODE
        $ErrorActionPreference = $savedPreference
        if ($buildExit -ne 0) { throw 'The Maven build failed; the application was not started.' }
    }

    $ErrorActionPreference = 'Continue'
    & docker compose up -d --wait --wait-timeout 90 db
    $dockerExit = $LASTEXITCODE
    $ErrorActionPreference = $savedPreference
    if ($dockerExit -ne 0) { throw 'MySQL did not become healthy. Check Docker Desktop and docker compose ps.' }

    $appProcess = Start-Process -FilePath $java -WorkingDirectory $projectRoot -ArgumentList @(
        '-jar', ('"' + $jar + '"'), "--spring.profiles.active=$Profile", '--server.address=127.0.0.1'
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $localDirectory 'local-app-start.log') `
        -RedirectStandardError (Join-Path $localDirectory 'local-app-error.log')
    $startedUtcTicks = $appProcess.StartTime.ToUniversalTime().Ticks.ToString()
    try {
        $isReady = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ($appProcess.HasExited) { throw 'The application exited. Check .local/local-app-start.log and .local/local-app-error.log.' }
            try {
                $ready = Invoke-RestMethod -Uri "$appUrl/api/ready" -TimeoutSec 2
                if ($ready.status -eq 'UP') { $isReady = $true; break }
            } catch [System.Net.WebException] { }
            Start-Sleep -Seconds 1
        }
        if (-not $isReady) { throw 'The application did not become ready within 60 checks.' }
        @{ pid = $appProcess.Id; startedUtcTicks = $startedUtcTicks; port = $Port; profile = $Profile } |
            ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    } catch {
        if (-not $appProcess.HasExited) { $appProcess.Kill() }
        throw
    }
    Write-Output "Local application is ready at $appUrl/ (PID $($appProcess.Id), profile $Profile)."
    if ($Profile -eq 'demo') {
        Write-Output 'Sign in as demo_instructor1 or demo_student1 using the private password in .local/demo-password.txt.'
    }
} finally { Pop-Location }
