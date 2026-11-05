param(
  [Parameter(Mandatory = $true)][string]$Exe,
  [string[]]$Arguments = @()
)
$ErrorActionPreference = "Stop"

$Data = Join-Path $env:TEMP ("holeshield-smoke-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $Data | Out-Null
$Log = Join-Path $Data "app.log"
$Err = Join-Path $Data "app.err"
$Process = $null

try {
  Write-Host "smoke: starting $Exe"
  $AllArgs = @($Arguments) + @("--headless", "--data-dir", $Data, "--port", "0", "--web-port", "0")
  $Process = Start-Process -FilePath $Exe -ArgumentList $AllArgs -PassThru `
    -RedirectStandardOutput $Log -RedirectStandardError $Err

  $DnsPort = ""
  $WebPort = ""
  for ($i = 0; $i -lt 120; $i++) {
    if (Test-Path $Log) {
      $Text = Get-Content $Log -Raw -ErrorAction SilentlyContinue
      if ($Text -match 'DNS:\s+\S+:(\d+)') { $DnsPort = $Matches[1] }
      if ($Text -match 'Dashboard: http://[^:]+:(\d+)/admin/') { $WebPort = $Matches[1] }
    }
    if ($DnsPort -and $WebPort) { break }
    if ($Process.HasExited) { break }
    Start-Sleep -Milliseconds 250
  }
  if (-not $DnsPort -or -not $WebPort) {
    Write-Host (Get-Content $Log -Raw -ErrorAction SilentlyContinue)
    Write-Host (Get-Content $Err -Raw -ErrorAction SilentlyContinue)
    throw "app did not report ports"
  }
  Write-Host "smoke: dns=$DnsPort web=$WebPort"

  $Base = "http://127.0.0.1:$WebPort"
  $Ready = $false
  for ($i = 0; $i -lt 80; $i++) {
    try {
      Invoke-RestMethod "$Base/api/health" -TimeoutSec 2 | Out-Null
      $Ready = $true
      break
    } catch {
      Start-Sleep -Milliseconds 250
    }
  }
  if (-not $Ready) { throw "dashboard did not become ready" }

  $Page = Invoke-WebRequest "$Base/admin/" -UseBasicParsing -TimeoutSec 8
  if ($Page.Content -notmatch "Hole Shield") { throw "dashboard body missing" }

  $Blocked = (& nslookup.exe "-port=$DnsPort" doubleclick.net 127.0.0.1 2>&1 | Out-String)
  if ($Blocked -notmatch "0\.0\.0\.0") { throw "blocked query did not return 0.0.0.0: $Blocked" }
  $Allowed = (& nslookup.exe "-port=$DnsPort" example.com 127.0.0.1 2>&1 | Out-String)
  if ($Allowed -notmatch "Address(es)?:\s+[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+") {
    throw "allowed query did not resolve: $Allowed"
  }

  $Summary = Invoke-RestMethod "$Base/api/stats/summary" -TimeoutSec 8
  $Queries = (Invoke-RestMethod "$Base/api/queries?length=40" -TimeoutSec 8).queries
  if ($Summary.queries.total -lt 2) { throw "summary total too low" }
  if ($Summary.queries.blocked -lt 1) { throw "summary blocked too low" }
  if (-not ($Queries | Where-Object { $_.domain -eq "doubleclick.net" -and $_.blocked })) {
    throw "blocked query missing from the log"
  }
  if (-not ($Queries | Where-Object { $_.domain -eq "example.com" -and $_.upstream -eq "DoH" })) {
    throw "DoH query missing from the log"
  }
  Write-Host ("smoke: api ok, total={0} blocked={1}" -f `
    $Summary.queries.total, $Summary.queries.blocked)
  Write-Host "smoke: PASS"
} finally {
  if ($Process -and -not $Process.HasExited) {
    Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
  }
  Remove-Item -Recurse -Force $Data -ErrorAction SilentlyContinue
}
