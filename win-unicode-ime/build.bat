@echo off
setlocal
cd /d "%~dp0"
echo === Building WinUnicodeIME.exe ===

set CSC=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe
if not exist "%CSC%" (
    echo ERROR: C# compiler not found! Install .NET Framework 4.x SDK.
    exit /b 1
)

"%CSC%" /target:winexe /out:WinUnicodeIME.exe /platform:anycpu /optimize+ /r:System.Windows.Forms.dll /r:System.Drawing.dll Program.cs

if %ERRORLEVEL% equ 0 (
    echo ======================================
    echo   BUILD SUCCESS!
    echo   Output: WinUnicodeIME.exe
    echo ======================================
    dir WinUnicodeIME.exe
) else (
    echo BUILD FAILED!
    exit /b 1
)
endlocal
