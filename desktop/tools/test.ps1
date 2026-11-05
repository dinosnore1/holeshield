$ErrorActionPreference = "Stop"
$Root = Resolve-Path "$PSScriptRoot\..\.."
& "$PSScriptRoot\build.ps1"
$ClassPath = Join-Path $Root "desktop\build\classes"
$JavaArgs = @(
  "-Djava.awt.headless=true",
  "-cp", $ClassPath,
  "com.dino.holeshield.DesktopTest"
)
& "$env:JAVA_HOME\bin\java.exe" @JavaArgs
