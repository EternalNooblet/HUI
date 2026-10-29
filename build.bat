@echo off
rem Build HUI for Windows 11. Produces hui.exe with an embedded build timestamp.
rem Same flags as build.sh: static binary, no cgo, stripped.
setlocal

set GOEXE=go
where go >nul 2>nul
if errorlevel 1 (
    if exist "%ProgramFiles%\Go\bin\go.exe" set GOEXE=%ProgramFiles%\Go\bin\go.exe
    if exist "%LOCALAPPDATA%\Programs\Go\bin\go.exe" set GOEXE=%LOCALAPPDATA%\Programs\Go\bin\go.exe
)
where "%GOEXE%" >nul 2>nul
if errorlevel 1 if not exist "%GOEXE%" (
    echo error: go toolchain not found ^(looked in PATH, %%ProgramFiles%%\Go, %%LOCALAPPDATA%%\Programs\Go^) 1>&2
    exit /b 1
)

for /f %%i in ('powershell -NoProfile -Command "[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')"') do set TS=%%i
if not defined TS set TS=1

set CGO_ENABLED=0
set GOOS=windows
set GOARCH=amd64

cd /d "%~dp0"
if not exist builds mkdir builds
"%GOEXE%" build -trimpath -ldflags "-s -w -X main.buildStamp=%TS%" -o builds\hui.exe .
if errorlevel 1 (
    echo build failed 1>&2
    exit /b 1
)
echo built builds\hui.exe (build %TS%)
