$ErrorActionPreference = "Stop"
$Root = Resolve-Path "$PSScriptRoot\..\.."
$Jdk = $env:JAVA_HOME
if (-not $Jdk) { throw "Set JAVA_HOME to a JDK 17 installation." }
& "$PSScriptRoot\build.ps1"
$Out = Join-Path $Root "desktop\build"
$Dest = Join-Path $Out "dist"
$Icons = Join-Path $Out "icons"
Remove-Item -Recurse -Force $Dest, $Icons -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Dest, $Icons | Out-Null
& "$Jdk\bin\java.exe" -cp "$Out\classes" com.dino.holeshield.IconGenerator "$Icons\icon.png" | Out-Null
Copy-Item "$Out\holeshield-desktop.jar" "$Dest\holeshield-desktop-2.3.2.jar" -Force

# Write a 256x256 PNG-backed ICO for the Windows installers.
Add-Type -AssemblyName System.Drawing
$Source = [System.Drawing.Image]::FromFile("$Icons\icon.png")
$Bitmap = New-Object System.Drawing.Bitmap 256, 256
$Graphics = [System.Drawing.Graphics]::FromImage($Bitmap)
$Graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$Graphics.DrawImage($Source, 0, 0, 256, 256)
$Memory = New-Object IO.MemoryStream
$Bitmap.Save($Memory, [System.Drawing.Imaging.ImageFormat]::Png)
$Png = $Memory.ToArray()
$Graphics.Dispose(); $Bitmap.Dispose(); $Source.Dispose(); $Memory.Dispose()
$Ico = "$Icons\icon.ico"
$Stream = [IO.File]::Create($Ico)
$Writer = New-Object IO.BinaryWriter($Stream)
$Writer.Write([UInt16]0); $Writer.Write([UInt16]1); $Writer.Write([UInt16]1)
$Writer.Write([Byte]0); $Writer.Write([Byte]0); $Writer.Write([Byte]0)
$Writer.Write([Byte]0); $Writer.Write([UInt16]1); $Writer.Write([UInt16]32)
$Writer.Write([UInt32]$Png.Length); $Writer.Write([UInt32]22)
$Writer.Write($Png)
$Writer.Close()

$Stage = Join-Path $Out "stage"
Remove-Item -Recurse -Force $Stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Stage | Out-Null
Copy-Item "$Out\holeshield-desktop.jar" $Stage -Force

$Name = "Hole Shield"
$Version = "2.3.2"
$Base = @(
  "--name", $Name,
  "--app-version", $Version,
  "--input", $Stage,
  "--main-jar", "holeshield-desktop.jar",
  "--main-class", "com.dino.holeshield.DesktopApp",
  "--dest", $Dest,
  "--vendor", "Dino",
  "--description", "Local DNS filtering for Windows, Linux and macOS",
  "--java-options", "-Xmx256m",
  "--add-modules", "java.base,java.desktop,java.net.http,jdk.httpserver,java.logging,jdk.crypto.ec",
  "--icon", "$Icons\icon.ico"
)
& "$Jdk\bin\jpackage.exe" --type app-image @Base
$AppDir = Join-Path $Dest $Name
if (Test-Path $AppDir) {
  Compress-Archive -Path $AppDir -DestinationPath "$Dest\holeshield-windows-portable-$Version.zip" -Force
}
$Wix = Get-Command candle.exe -ErrorAction SilentlyContinue
if ($Wix) {
  & "$Jdk\bin\jpackage.exe" --type msi @Base --win-menu --win-shortcut --win-dir-chooser
  & "$Jdk\bin\jpackage.exe" --type exe @Base --win-menu --win-shortcut --win-dir-chooser
} else {
  Write-Host "WiX not found; built the portable app image only."
}
Get-ChildItem $Dest | Select-Object Name
