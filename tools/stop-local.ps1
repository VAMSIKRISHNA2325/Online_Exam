$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $projectRoot '.local/local-app.json'
if (-not (Test-Path -LiteralPath $statePath)) {
    Write-Output 'No application started by tools/start-local.ps1 is recorded.'
    return
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$appProcess = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
if ($appProcess) {
    if ($appProcess.ProcessName -ne 'java' -or
        $appProcess.StartTime.ToUniversalTime().Ticks.ToString() -ne $state.startedUtcTicks) {
        throw 'The recorded PID now belongs to a different process. It was left running; inspect .local/local-app.json.'
    }
    Stop-Process -Id $appProcess.Id
    $appProcess.WaitForExit(10000) | Out-Null
    if (-not $appProcess.HasExited) { throw 'The application has not stopped yet; its process record was retained.' }
}
Remove-Item -LiteralPath $statePath
Write-Output 'Local application stopped. MySQL remains running and its data is preserved.'
