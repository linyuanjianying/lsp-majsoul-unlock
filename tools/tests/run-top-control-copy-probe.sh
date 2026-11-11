#!/bin/sh
# JVM pure-logic regression for the redesigned top control copy (GameMenuText.kt).
#
# Read-only w.r.t. the project: it compiles the real translation table together with
# TopControlCopyProbe.kt against the already-built arm64Debug classes (-Xfriend-paths
# exposes `internal`) and only calls pure functions — no Context, window, service or
# provider is created. It answers the questions the redesigned capsule / header / cards
# depend on:
#
#   - does every Chinese phrase GameTopControl draws have an en / ja translation?
#   - do the capsule phrase and the state dot always agree (green never next to "off")?
#   - does the header pill follow the same precedence as stateColor()?
#   - do the capsule phrases stay short enough for a capsule that never truncates?
#
# GameMenuText.kt is compiled from source into a private output directory and put
# *first* on the classpath, so an edit is picked up without Gradle. Every other class
# still comes from the last build; the GameTopControl / GameTopUi Java sources are not
# involved (they need a device to run), only their copy is mirrored in the probe.
#
# Usage: tools/tests/run-top-control-copy-probe.sh
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
CLASSES="$PROJ/hook-probe/build/tmp/kotlin-classes/arm64Debug"
SRC="$PROJ/hook-probe/src/main/java/com/yefeng/majmax/hookprobe"
OUT="$PROJ/hook-probe/build/top-control-probe"
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=${GRADLE_USER_HOME:-/root/.gradle}/caches/modules-2/files-2.1
JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

[ -d "$CLASSES" ] || { echo "missing $CLASSES; build the app once first" >&2; exit 2; }
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR; set ANDROID_HOME" >&2; exit 2; }

find_jar() { find "$1" -name "$2" ! -name '*sources*' | head -1; }
KOTLINC=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-*.jar')
STDLIB=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-2.*.jar')
JSON=$(find_jar "$GC/org.json/json" 'json-*.jar')
COROUTINES=$(find_jar "$GC/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" 'kotlinx-coroutines-core-jvm-*.jar')
[ -n "$KOTLINC" ] || { echo "missing kotlin-compiler-embeddable in $GC" >&2; exit 2; }
[ -n "$STDLIB" ] || { echo "missing kotlin-stdlib in $GC" >&2; exit 2; }

# Compose runtime ships as an AAR and is only needed by ManagerLanguage.kt.
COMPOSE_JAR=""
COMPOSE_AAR=$(find "$GC/androidx.compose.runtime/runtime-android" -name '*.aar' ! -name '*sources*' 2>/dev/null | head -1)
if [ -n "$COMPOSE_AAR" ]; then
  COMPOSE_JAR="$OUT/compose-runtime-classes.jar"
  if [ ! -s "$COMPOSE_JAR" ]; then
    rm -rf "$OUT/compose-runtime" && mkdir -p "$OUT/compose-runtime"
    if (cd "$OUT/compose-runtime" && unzip -o -q "$COMPOSE_AAR" classes.jar 2>/dev/null) &&
       [ -s "$OUT/compose-runtime/classes.jar" ]; then
      mv "$OUT/compose-runtime/classes.jar" "$COMPOSE_JAR"
    fi
  fi
  [ -s "$COMPOSE_JAR" ] || COMPOSE_JAR=""
fi

mkdir -p "$OUT"
KCP="$OUT/compiler-classpath.txt"
[ -s "$KCP" ] || find "$GC/org.jetbrains.kotlin" "$GC/org.jetbrains.intellij.deps" "$GC/org.jetbrains" \
  "$GC/trove4j" "$GC/org.jetbrains.kotlinx" "$GC/org.jetbrains.gradle" "$GC/org.json" \
  -type f -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':' > "$KCP"

LIBPATH="$CLASSES:$STDLIB:$ANDROID_JAR${JSON:+:$JSON}${COROUTINES:+:$COROUTINES}"
[ -n "$COMPOSE_JAR" ] && LIBPATH="$LIBPATH:$COMPOSE_JAR"

rm -rf "$OUT/classes"
"$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$LIBPATH" \
  -Xfriend-paths="$CLASSES" -jvm-target 21 -nowarn \
  -d "$OUT/classes" \
  "$SRC/helper/GameMenuText.kt" \
  "$PROJ/tools/tests/TopControlCopyProbe.kt"

"$JAVA" -cp "$OUT/classes:$LIBPATH" TopControlCopyProbeKt
