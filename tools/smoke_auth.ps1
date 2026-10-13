param([string]$BaseUrl = 'http://127.0.0.1:8080')

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:DEMO_PASSWORD)) {
    throw 'Set DEMO_PASSWORD privately before running the authentication smoke check.'
}

function RequestStatus($method, $path, $webSession, $body, $headers) {
    try {
        $request = @{ Uri = "$BaseUrl$path"; Method = $method; WebSession = $webSession; UseBasicParsing = $true }
        if ($null -ne $body) { $request.Body = $body }
        if ($null -ne $headers) { $request.Headers = $headers }
        return [int](Invoke-WebRequest @request).StatusCode
    } catch [System.Net.WebException] {
        if ($null -eq $_.Exception.Response) { throw }
        return [int]$_.Exception.Response.StatusCode
    }
}

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$csrf = Invoke-RestMethod -Uri "$BaseUrl/api/csrf" -WebSession $session
$before = $session.Cookies.GetCookies([uri]$BaseUrl)['JSESSIONID'].Value
$missingCsrf = RequestStatus 'POST' '/login' $session @{ username='demo_student1'; password=$env:DEMO_PASSWORD } $null
$badPasswordSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$badToken = Invoke-RestMethod -Uri "$BaseUrl/api/csrf" -WebSession $badPasswordSession
$badPassword = RequestStatus 'POST' '/login' $badPasswordSession @{ username='demo_student1'; password='incorrect'; _csrf=$badToken.token } $null
$validLogin = RequestStatus 'POST' '/login' $session @{ username='demo_student1'; password=$env:DEMO_PASSWORD; _csrf=$csrf.token } $null
$after = $session.Cookies.GetCookies([uri]$BaseUrl)['JSESSIONID'].Value
$me = Invoke-RestMethod -Uri "$BaseUrl/api/me" -WebSession $session
$meJson = $me | ConvertTo-Json -Compress
$fresh = Invoke-RestMethod -Uri "$BaseUrl/api/csrf" -WebSession $session
$missingLogoutCsrf = RequestStatus 'POST' '/logout' $session $null $null
$stillAuthenticated = RequestStatus 'GET' '/api/me' $session $null $null
$validLogout = RequestStatus 'POST' '/logout' $session $null @{ 'X-CSRF-TOKEN'=$fresh.token }
$afterLogout = RequestStatus 'GET' '/api/me' $session $null $null

$result = [ordered]@{
    missingLoginCsrf = $missingCsrf
    wrongPassword = $badPassword
    validLogin = $validLogin
    sessionIdChanged = ($before -ne $after)
    meUsername = $me.username
    meRole = $me.role
    meHasNoPasswordOrHash = (-not $meJson.Contains('password') -and -not $meJson.Contains('bcrypt'))
    missingLogoutCsrf = $missingLogoutCsrf
    firstSessionStillAuthenticated = $stillAuthenticated
    validLogout = $validLogout
    oldSessionAfterLogout = $afterLogout
}
$result | ConvertTo-Json
if ($missingCsrf -ne 403 -or $badPassword -ne 401 -or $validLogin -ne 204 -or
        $before -eq $after -or $me.username -ne 'demo_student1' -or $me.role -ne 'STUDENT' -or
        $meJson.Contains('password') -or $meJson.Contains('bcrypt') -or
        $missingLogoutCsrf -ne 403 -or $stillAuthenticated -ne 200 -or
        $validLogout -ne 204 -or $afterLogout -ne 401) {
    exit 1
}
