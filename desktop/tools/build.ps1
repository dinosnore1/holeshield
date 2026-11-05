$ErrorActionPreference = "Stop"
$Root = Resolve-Path "$PSScriptRoot\..\.."
$Jdk = $env:JAVA_HOME
if (-not $Jdk -or -not (Test-Path "$Jdk\bin\javac.exe")) {
  throw "Set JAVA_HOME to a JDK 17 installation."
}
$Out = Join-Path $Root "desktop\build"
Remove-Item -Recurse -Force "$Out\classes", "$Out\holeshield-desktop.jar" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$Out\classes" | Out-Null
$Sources = Get-ChildItem (Join-Path $Root "desktop\src") -Recurse -Filter *.java |
  ForEach-Object { $_.FullName }
$Shared = @(
  "src\com\dino\holeshield\DnsPacket.java",
  "src\com\dino\holeshield\Gravity.java",
  "src\com\dino\holeshield\HostsParser.java",
  "src\com\dino\holeshield\Blocklists.java",
  "src\com\dino\holeshield\WebAuth.java"
) | ForEach-Object { Join-Path $Root $_ }
$All = $Sources + $Shared
$ArgFile = Join-Path $Out "sources.txt"
$All | Set-Content -Encoding UTF8 $ArgFile
& "$Jdk\bin\javac.exe" --release 17 -encoding UTF-8 -d "$Out\classes" "@$ArgFile"
Copy-Item (Join-Path $Root "desktop\resources\*") "$Out\classes" -Force
Copy-Item (Join-Path $Root "assets\dashboard.html") "$Out\classes\dashboard.html" -Force
& "$Jdk\bin\jar.exe" --create --file "$Out\holeshield-desktop.jar" `
  --main-class com.dino.holeshield.DesktopApp -C "$Out\classes" .
Write-Host "built: $Out\holeshield-desktop.jar"
