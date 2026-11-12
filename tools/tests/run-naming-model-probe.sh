#!/bin/sh
# JVM pure-logic regression for the shared opponent-naming model.
#
# Compiles the REAL manager/OpponentNaming.kt together with the probe, so the checks run against
# the same class the provider, the in-game card and the floating window use — not a copy. The
# model has no Context or View dependency, so the only Android artifact needed is a
# SharedPreferences-free classpath entry (the app's own android.jar for the Context type that
# `OpponentNamingSettings` mentions) plus a real org.json.
#
# Nothing is written under hook-probe/build (a Gradle build owns that directory); the output goes
# to /tmp/naming-model-probe, override with NAMING_PROBE_OUT.
#
# Usage: tools/tests/run-naming-model-probe.sh
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$PROJ/hook-probe/src/main/java/com/yefeng/majmax/hookprobe/manager/OpponentNaming.kt"
PROBE="$PROJ/tools/tests/NamingModelProbe.kt"
OUT=${NAMING_PROBE_OUT:-/tmp/naming-model-probe}
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=${GRADLE_USER_HOME:-/root/.gradle}/caches/modules-2/files-2.1
JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

find_jar() { find "$1" -name "$2" ! -name '*sources*' 2>/dev/null | head -1; }
KOTLINC=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-*.jar')
STDLIB=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-2.*.jar')
JSON=$(find_jar "$GC/org.json/json" 'json-*.jar')

[ -f "$SRC" ] || { echo "missing $SRC" >&2; exit 2; }
[ -f "$PROBE" ] || { echo "missing $PROBE" >&2; exit 2; }
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR; set ANDROID_HOME" >&2; exit 2; }
[ -n "$KOTLINC" ] || { echo "missing kotlin-compiler-embeddable in $GC" >&2; exit 2; }
[ -n "$STDLIB" ] || { echo "missing kotlin-stdlib in $GC" >&2; exit 2; }
[ -n "$JSON" ] || { echo "missing org.json in $GC" >&2; exit 2; }

KCP="$OUT/compiler-classpath.txt"
mkdir -p "$OUT"
[ -s "$KCP" ] || find "$GC/org.jetbrains.kotlin" "$GC/org.jetbrains.intellij.deps" \
  "$GC/org.jetbrains" "$GC/trove4j" "$GC/org.jetbrains.kotlinx" "$GC/org.jetbrains.gradle" \
  "$GC/org.json" -type f -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':' > "$KCP"

echo "== compile real OpponentNaming.kt + probe =="
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
"$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STDLIB:$JSON:$ANDROID_JAR" \
  -jvm-target 21 -nowarn -d "$OUT/classes" "$SRC" "$PROBE"

echo "== run =="
"$JAVA" -cp "$OUT/classes:$STDLIB:$JSON:$ANDROID_JAR" NamingModelProbeKt
