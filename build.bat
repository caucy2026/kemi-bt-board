@echo off
setlocal

set JAVA_HOME=D:\work\ai_code\tools\jdk-17\jdk-17.0.14+7
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d D:\work\ai_code\kboard

echo === Cleaning old Gradle daemons ===
taskkill /f /im java.exe 2>nul
timeout /t 3 /nobreak >nul

echo === Building APK ===
"%JAVA_HOME%\bin\java.exe" -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleRelease --no-daemon

echo === Build exit code: %ERRORLEVEL% ===

if exist "app\build\outputs\apk\release\app-release.apk" (
    echo =======================================
    echo   APK BUILD SUCCESS!
    echo   Location: app\build\outputs\apk\release\app-release.apk
    echo =======================================
    dir "app\build\outputs\apk\release\app-release.apk"
) else (
    echo APK build failed or not found.
    dir "app\build\outputs\apk\release" 2>nul
)

endlocal
