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

@rem Resolve Java in order:
@rem 1) JAVA_HOME (CI and local)
@rem 2) java.exe on PATH
@rem 3) Local fallback JDK path used by the Hytale toolkit
set "JAVACMD="

if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVACMD=%JAVA_HOME%\bin\java.exe"
    )
)

if "%JAVACMD%"=="" (
    for %%I in (java.exe) do set "JAVACMD=%%~$PATH:I"
)

if "%JAVACMD%"=="" (
    set "LOCAL_JAVA=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot\bin\java.exe"
    if exist "%LOCAL_JAVA%" (
        set "JAVACMD=%LOCAL_JAVA%"
    )
)

if "%JAVACMD%"=="" (
    echo Error: Java runtime not found. Set JAVA_HOME or add java.exe to PATH.
    exit /b 1
)

set MAVEN_PROJECTBASEDIR=%BASEDIR%

"%JAVACMD%" %MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory="%BASEDIR%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
