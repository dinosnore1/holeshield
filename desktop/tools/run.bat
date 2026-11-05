@echo off
set ROOT=%~dp0..\..
set JAR=%ROOT%\desktop\build\holeshield-desktop.jar
if not exist "%JAR%" call "%ROOT%\desktop\tools\build.ps1"
java -jar "%JAR%" %*
