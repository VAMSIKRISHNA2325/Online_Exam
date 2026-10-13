$ErrorActionPreference = 'Stop'
$settings = @{}
foreach ($line in Get-Content -LiteralPath '.env') {
    if ($line -match '^[A-Z_]+=') {
        $pair = $line.Split('=', 2)
        $settings[$pair[0]] = $pair[1]
    }
}
$env:JAVA_HOME = (Resolve-Path '.local\jdk\jdk-21.0.12.1+1').Path
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/online_exam_e2e?connectionTimeZone=UTC'
$env:DB_USER = 'online_exam'
$env:DB_PASSWORD = $settings['MYSQL_PASSWORD']
$env:DEMO_PASSWORD = (Get-Content -LiteralPath '.local\demo-password.txt' -Raw).Trim()
$env:DEBUG = 'false'

$appProcess = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList @(
    '-jar', 'target/online-exam-0.1.0-SNAPSHOT.jar', '--spring.profiles.active=demo'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput '.local/p09-03-pilot-app.log' `
    -RedirectStandardError '.local/p09-03-pilot-app-error.log'
try {
    $ready = $false
    for ($i = 0; $i -lt 90; $i++) {
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/ready' `
                -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Packaged application did not become ready' }
    python tools/check_ten_student_pilot.py
    if ($LASTEXITCODE -ne 0) { throw 'Ten-student pilot failed' }
} finally {
    if (Get-Process -Id $appProcess.Id -ErrorAction SilentlyContinue) {
        Stop-Process -Id $appProcess.Id -Force
    }
}
