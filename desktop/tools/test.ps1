$ErrorActionPreference = "Stop"
$Root = Resolve-Path "$PSScriptRoot\..\.."
& "$PSScriptRoot\build.ps1"
& "$env:JAVA_HOME\bin\java.exe" -Djava.awt.headless=true `
  -cp (Join-Path $Root "desktop\build\classes") com.dino.holeshield.DesktopTest
