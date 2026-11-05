param(
  [Parameter(Mandatory = $true)][string]$Exe,
  [string[]]$Arguments = @()
)
$ErrorActionPreference = "Stop"

function Read-U16([byte[]]$Bytes, [int]$Offset) {
  return ([int]$Bytes[$Offset] -shl 8) -bor [int]$Bytes[$Offset + 1]
}

function Invoke-DnsA([string]$Name, [int]$Port) {
  $Id = Get-Random -Minimum 1 -Maximum 65535
  $Stream = New-Object System.IO.MemoryStream
  $Writer = New-Object System.IO.BinaryWriter($Stream)
  $Writer.Write([UInt16]$Id)
  $Writer.Write([UInt16]0x0100)
  $Writer.Write([UInt16]1)
  $Writer.Write([UInt16]0)
  $Writer.Write([UInt16]0)
  $Writer.Write([UInt16]0)
  foreach ($Label in $Name.Split(".")) {
    $Bytes = [Text.Encoding]::ASCII.GetBytes($Label)
    $Writer.Write([byte]$Bytes.Length)
    $Writer.Write($Bytes)
  }
  $Writer.Write([byte]0)
  $Writer.Write([UInt16]1)
  $Writer.Write([UInt16]1)
  $Writer.Flush()
  $Payload = $Stream.ToArray()
  $Writer.Dispose()
  $Stream.Dispose()

  $Udp = New-Object System.Net.Sockets.UdpClient
  $Udp.Client.ReceiveTimeout = 5000
  $Udp.Connect("127.0.0.1", $Port)
  [void]$Udp.Send($Payload, $Payload.Length)
  $Remote = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
  $Response = $Udp.Receive([ref]$Remote)
  $Udp.Close()

  if ($Response.Length -lt 12) { throw "short DNS response" }
  $Qd = Read-U16 $Response 4
  $An = Read-U16 $Response 6
  $Offset = 12
  for ($Q = 0; $Q -lt $Qd; $Q++) {
    while ($true) {
      $Length = [int]$Response[$Offset]
      if (($Length -band 0xC0) -eq 0xC0) { $Offset += 2; break }
      if ($Length -eq 0) { $Offset++; break }
      $Offset += 1 + $Length
    }
    $Offset += 4
  }
  $Addresses = @()
  for ($I = 0; $I -lt $An; $I++) {
    $Length = [int]$Response[$Offset]
    if (($Length -band 0xC0) -eq 0xC0) {
      $Offset += 2
    } else {
      while ($true) {
        $Length = [int]$Response[$Offset]
        if ($Length -eq 0) { $Offset++; break }
        $Offset += 1 + $Length
      }
    }
    $Type = Read-U16 $Response $Offset
    $RdLength = Read-U16 $Response ($Offset + 8)
    $RData = $Offset + 10
    if ($Type -eq 1 -and $RdLength -eq 4) {
      $Addresses += ("{0}.{1}.{2}.{3}" -f $Response[$RData],
        $Response[$RData + 1], $Response[$RData + 2], $Response[$RData + 3])
    }
    $Offset = $RData + $RdLength
  }
  return $Addresses
}

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

  $Blocked = @(Invoke-DnsA "doubleclick.net" ([int]$DnsPort))
  if ($Blocked -notcontains "0.0.0.0") {
    throw "blocked query did not return 0.0.0.0: $($Blocked -join ', ')"
  }
  $Allowed = @(Invoke-DnsA "example.com" ([int]$DnsPort))
  if ($Allowed.Count -eq 0 -or $Allowed -contains "0.0.0.0") {
    throw "allowed query did not resolve: $($Allowed -join ', ')"
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
