@echo off
rem ============================================================
rem  Jhermes CLI launcher
rem  Forces UTF-8 for file encoding and console output (JDK 19+)
rem ============================================================
setlocal
chcp 65001 >nul
set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%~dp0hermes.jar" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
