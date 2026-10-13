$ErrorActionPreference = 'Stop'
$settings = @{}
foreach ($line in Get-Content -LiteralPath '.env') {
    if ($line -match '^[A-Z_]+=') {
        $pair = $line.Split('=', 2)
        $settings[$pair[0]] = $pair[1]
    }
}
$env:JAVA_HOME = (Resolve-Path '.local\jdk\jdk-21.0.12.1+1').Path
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/online_exam?connectionTimeZone=UTC'
$env:DB_USER = 'online_exam'
$env:DB_PASSWORD = $settings['MYSQL_PASSWORD']
$env:DEBUG = 'false'

try {
    Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/ready' -UseBasicParsing -TimeoutSec 2 | Out-Null
    throw 'Port 8080 already serves an application; release startup check needs its own listener'
} catch [System.Net.WebException] {
    # Expected: no listener is running.
}

$appProcess = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList @(
    '-jar', 'target/online-exam-0.1.0-SNAPSHOT.jar'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput '.local/p10-01-release-app.log' `
    -RedirectStandardError '.local/p10-01-release-app-error.log'
try {
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ($appProcess.HasExited) { throw "Packaged process exited with code $($appProcess.ExitCode)" }
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/ready' -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Packaged application did not become ready within 60 seconds' }
    $page = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/login.html' -UseBasicParsing -TimeoutSec 5
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch 'Examroom') {
        throw 'Packaged login page did not load'
    }
    $anonymous = $null
    try {
        Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/me' -UseBasicParsing -TimeoutSec 5 | Out-Null
    } catch [System.Net.WebException] {
        $anonymous = [int]$_.Exception.Response.StatusCode
    }
    if ($anonymous -ne 401) { throw "Anonymous /api/me returned $anonymous instead of 401" }
    Write-Output 'PASS: packaged JAR started with external DB_URL/DB_USER/DB_PASSWORD in normal profile; MySQL-backed readiness 200, login page 200, anonymous /api/me 401.'
} finally {
    if (Get-Process -Id $appProcess.Id -ErrorAction SilentlyContinue) {
        Stop-Process -Id $appProcess.Id -Force
    }
}
