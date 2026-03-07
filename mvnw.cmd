@echo off
@rem Maven Wrapper script for Windows
@rem Uses maven-wrapper.jar to download and run Maven

setlocal

set BASEDIR=%~dp0
@rem Remove trailing backslash if present
if "%BASEDIR:~-1%"=="\" set BASEDIR=%BASEDIR:~0,-1%

set WRAPPER_JAR=%BASEDIR%\.mvn\wrapper\maven-wrapper.jar

if not exist "%WRAPPER_JAR%" (
    echo Error: maven-wrapper.jar not found at %WRAPPER_JAR%
    echo Please ensure the .mvn\wrapper directory exists with maven-wrapper.jar
    exit /b 1
)

@rem Prefer local toolkit JDK, then JAVA_HOME, then PATH.
set "LOCAL_JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot\bin\java.exe"
set "JAVACMD="

if exist "%LOCAL_JAVA_EXE%" (
    set "JAVACMD=%LOCAL_JAVA_EXE%"
) else (
    if not "%JAVA_HOME%"=="" (
        if exist "%JAVA_HOME%\bin\java.exe" (
            set "JAVACMD=%JAVA_HOME%\bin\java.exe"
        )
    )
)

if "%JAVACMD%"=="" (
    set "JAVACMD=java.exe"
)

if not exist "%JAVACMD%" (
    for %%I in ("%JAVACMD%") do set "JAVA_ON_PATH=%%~$PATH:I"
)
if not exist "%JAVACMD%" if "%JAVA_ON_PATH%"=="" (
    echo Error: Java runtime not found. Set JAVA_HOME or ensure java.exe is on PATH.
    exit /b 1
)

set MAVEN_PROJECTBASEDIR=%BASEDIR%

"%JAVACMD%" %MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory="%BASEDIR%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
