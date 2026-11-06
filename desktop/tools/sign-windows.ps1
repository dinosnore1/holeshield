param(
  [string]$Dist = "",
  [string]$Version = "2.3.4",
  [string]$AppExe = ""
)
$ErrorActionPreference = "Stop"

$Root = Resolve-Path "$PSScriptRoot\..\.."
if (-not $Dist) { $Dist = Join-Path $Root "desktop\build\dist" }

$SignTool = (Get-Command signtool.exe -ErrorAction SilentlyContinue).Source
if (-not $SignTool) {
  $Candidate = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" `
    -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
    Sort-Object FullName -Descending | Select-Object -First 1
  if ($Candidate) { $SignTool = $Candidate.FullName }
}
if (-not $SignTool) { throw "signtool.exe not found; install the Windows SDK." }

$CertArgs = @()
if ($env:WINDOWS_CERT_PFX) {
  if (-not $env:WINDOWS_CERT_PASSWORD) {
    throw "WINDOWS_CERT_PASSWORD is required with WINDOWS_CERT_PFX."
  }
  $CertArgs = @("/f", $env:WINDOWS_CERT_PFX, "/p", $env:WINDOWS_CERT_PASSWORD)
} elseif ($env:WINDOWS_CERT_SHA1) {
  $CertArgs = @("/sha1", $env:WINDOWS_CERT_SHA1)
} else {
  throw "Set WINDOWS_CERT_PFX + WINDOWS_CERT_PASSWORD, or WINDOWS_CERT_SHA1."
}

$Timestamp = if ($env:WINDOWS_TIMESTAMP_URL) {
  $env:WINDOWS_TIMESTAMP_URL
} else {
  "http://timestamp.digicert.com"
}

$Files = @(Get-ChildItem $Dist -File -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -like "*$Version.msi" -or $_.Name -like "*$Version.exe" })
if ($AppExe) { $Files += Get-Item $AppExe }
if ($Files.Count -eq 0) {
  throw "No MSI/EXE found under $Dist for version $Version."
}

foreach ($File in $Files) {
  Write-Host "Signing $($File.FullName)"
  $SignArgs = @("sign", "/fd", "SHA256", "/td", "SHA256", "/tr", $Timestamp) +
    $CertArgs + @($File.FullName)
  & $SignTool @SignArgs
  if ($LASTEXITCODE -ne 0) { throw "signtool sign failed for $($File.Name)" }
  & $SignTool verify /pa /v $File.FullName
  if ($LASTEXITCODE -ne 0) { throw "signtool verify failed for $($File.Name)" }
}

Write-Host "Signed:"
$Files | ForEach-Object { Write-Host "  $($_.FullName)" }
