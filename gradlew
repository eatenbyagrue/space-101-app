#!/bin/sh

##############################################################################
# Gradle start up script for UN*X
##############################################################################

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD="maximum"

warn() { echo "$*"; }
die() { echo; echo "$*"; echo; exit 1; }

cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN*)  cygwin=true ;;
  Darwin*)  darwin=true ;;
  MSYS*|MINGW*) msys=true ;;
  NONSTOP*) nonstop=true ;;
esac

# Resolve script location
SCRIPT_DIR=`dirname "$0"`
APP_HOME=`cd "$SCRIPT_DIR" && pwd`
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ]; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' found in PATH."
fi

if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonstop" = "false" ]; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ $? -eq 0 ]; then
        [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] && MAX_FD="$MAX_FD_LIMIT"
        ulimit -n $MAX_FD 2>/dev/null
    fi
fi

save() {
    for i do printf %s\\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/"; done
    echo " "
}
APP_ARGS=`save "$@"`

eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" \
    -classpath "\"$CLASSPATH\"" \
    org.gradle.wrapper.GradleWrapperMain "$APP_ARGS"

exec "$JAVACMD" "$@"
