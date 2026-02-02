#!/bin/sh
# Maven Wrapper script
# Uses maven-wrapper.jar to download and run Maven

BASEDIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$BASEDIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Error: maven-wrapper.jar not found at $WRAPPER_JAR"
    echo "Please ensure the .mvn/wrapper directory exists with maven-wrapper.jar"
    exit 1
fi

# Use bundled JDK from Hytale Toolkit
JAVACMD="C:/Program Files/Eclipse Adoptium/jdk-25.0.1.8-hotspot/bin/java"

# Set MAVEN_PROJECTBASEDIR for multi-module support
export MAVEN_PROJECTBASEDIR="$BASEDIR"

exec "$JAVACMD" $MAVEN_OPTS \
    -Dmaven.multiModuleProjectDirectory="$BASEDIR" \
    -classpath "$WRAPPER_JAR" \
    org.apache.maven.wrapper.MavenWrapperMain "$@"
