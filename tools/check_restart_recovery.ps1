$ErrorActionPreference = 'Stop'
$settings = @{}
foreach ($line in Get-Content -LiteralPath '.env') {
    if ($line -match '^[A-Z_]+=') {
        $pair = $line.Split('=', 2)
        $settings[$pair[0]] = $pair[1]
    }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to your Java 21 JDK.' }
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/online_exam_e2e?connectionTimeZone=UTC'
$env:DB_USER = 'online_exam'
$env:DB_PASSWORD = $settings['MYSQL_PASSWORD']
$env:DEMO_PASSWORD = (Get-Content -LiteralPath '.local\demo-password.txt' -Raw).Trim()
$env:DEBUG = 'false'
$port = 8080
if ($env:E2E_PORT) { $port = [int]$env:E2E_PORT }
if ($port -lt 1024 -or $port -gt 65535) { throw 'Invalid E2E_PORT' }
$env:PORT = $port.ToString()
$url = "http://127.0.0.1:$port"
$probe = New-Object System.Net.Sockets.TcpClient
try {
    try { $probe.Connect('127.0.0.1', $port) } catch [System.Net.Sockets.SocketException] {
        if ($_.Exception.SocketErrorCode -ne [System.Net.Sockets.SocketError]::ConnectionRefused) { throw }
    }
    if ($probe.Connected) { throw 'Test port is occupied; select a free E2E_PORT.' }
} finally { $probe.Dispose() }
$script:appProcess = $null
$script:startCount = 0

function Start-DemoApp {
    $script:startCount++
    $suffix = $script:startCount
    $script:appProcess = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList @(
        '-jar', 'target/online-exam-0.1.0-SNAPSHOT.jar', '--spring.profiles.active=demo',
        '--server.address=127.0.0.1'
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput ".local/p08-04-restart-$suffix.log" `
        -RedirectStandardError ".local/p08-04-restart-$suffix-error.log"
    $ready = $false
    for ($i = 0; $i -lt 90; $i++) {
        if ($script:appProcess.HasExited) { throw 'Isolated test app exited before readiness.' }
        try {
            $response = Invoke-RestMethod -Uri "$url/api/ready" -TimeoutSec 2
            if ($response.status -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Packaged application did not become ready' }
}

function Stop-DemoApp {
    if ($script:appProcess -and (Get-Process -Id $script:appProcess.Id -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $script:appProcess.Id -Force
        Start-Sleep -Milliseconds 500
    }
    $script:appProcess = $null
}

try {
    Start-DemoApp
    python tools/check_restart_recovery.py prepare
    if ($LASTEXITCODE -ne 0) { throw 'Fixture preparation failed' }
    Stop-DemoApp
    python tools/check_restart_recovery.py expire
    if ($LASTEXITCODE -ne 0) { throw 'Controlled expiry setup failed' }
    Start-DemoApp
    python tools/check_restart_recovery.py verify
    if ($LASTEXITCODE -ne 0) { throw 'Restart verification failed' }
} finally {
    Stop-DemoApp
}
