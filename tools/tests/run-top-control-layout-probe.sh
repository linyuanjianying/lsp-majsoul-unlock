#!/bin/sh
# JVM layout-regression for the in-game menu (GameTopControl.java / GameTopUi.java).
#
# Builds the REAL menu on the reporter's device metrics (2376x1080 @ 2.75, menu host at
# SCALE .75) through tools/tests/jvm-shims/android-shim and asserts the compact chrome:
# the 42%x60% window box is untouched, header+tab+gaps stay under a quarter of the menu,
# the scroll band keeps >=65% (>=420 visible px), all three switch cards are in the first
# screenful, the fixed collapse row is gone, and the handle is a wide flat top-anchored pill
# whose dot and chevron share one row.
#
# The shim's measure passes are faithful for structure (children, weights, MATCH_PARENT,
# margins, paddings, minimum heights) and model a text row as padding + ~1.34em, since no
# font exists on the JVM. Nothing here is a pixel mock-up: the assertions are ratios,
# constants and draw-call shapes.
#
# Workaround for the "hidden class" javac bug that hit the build probe: the staging copy
# lives in its own directory, keeping TopControlLayoutProbe.kt the only source in it.
#
# Usage: tools/tests/run-top-control-layout-probe.sh
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$PROJ/hook-probe/src/main/java/com/yefeng/majmax/hookprobe"
SHIM="$PROJ/tools/tests/jvm-shims/android-shim"
KOTLIN_SRC="$(dirname "$(find "$SRC/helper" -name 'InGameLocalizer.kt' | head -1)")"
R_JAR="$PROJ/hook-probe/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/arm64Debug/processArm64DebugResources/R.jar"
OUT=${TOP_LAYOUT_PROBE_OUT:-/tmp/top-control-layout-probe}
GC=${GRADLE_USER_HOME:-/root/.gradle}/caches/modules-2/files-2.1
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
JAVAC=/usr/lib/jvm/java-21-openjdk-arm64/bin/javac
[ -x "$JAVA" ] || JAVA=java
[ -x "$JAVAC" ] || JAVAC=javac

[ -d "$SHIM" ] || { echo "missing $SHIM" >&2; exit 2; }
[ -n "$KOTLIN_SRC" ] || { echo "missing Kotlin sources under $SRC/helper" >&2; exit 2; }
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR; set ANDROID_HOME" >&2; exit 2; }

find_jar() { find "$1" -name "$2" ! -name '*sources*' 2>/dev/null | head -1; }
KOTLINC=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-*.jar')
STDLIB=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-2.*.jar')
[ -n "$KOTLINC" ] || { echo "missing kotlin-compiler-embeddable in $GC" >&2; exit 2; }
[ -n "$STDLIB" ] || { echo "missing kotlin-stdlib in $GC" >&2; exit 2; }
JSON=$(find_jar "$GC/org.json/json" 'json-*.jar')

mkdir -p "$OUT"
KCP="$OUT/compiler-classpath.txt"
[ -s "$KCP" ] || find "$GC/org.jetbrains.kotlin" "$GC/org.jetbrains.intellij.deps" "$GC/org.jetbrains" \
  "$GC/trove4j" "$GC/org.jetbrains.kotlinx" "$GC/org.jetbrains.gradle" "$GC/org.json" \
  -type f -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':' > "$KCP"

echo "== 1/5 shim =="
rm -rf "$OUT/shim" && mkdir -p "$OUT/shim"
"$JAVAC" -nowarn -source 21 -target 21 -d "$OUT/shim" $(find "$SHIM" -name '*.java')

echo "== 2/5 real InGameLocalizer + minimal manager collaborators =="
rm -rf "$OUT/kotlin" && mkdir -p "$OUT/kotlin"
"$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$OUT/shim:$STDLIB:$ANDROID_JAR${JSON:+:$JSON}" \
  -jvm-target 21 -nowarn -d "$OUT/kotlin" \
  "$SRC/helper/InGameLocalizer.kt" \
  "$PROJ/tools/tests/jvm-shims/support/com/yefeng/majmax/hookprobe/helper/GameMenuText.kt" \
  "$PROJ/tools/tests/jvm-shims/support/com/yefeng/majmax/hookprobe/manager/ManagerLanguage.kt" \
  "$PROJ/tools/tests/jvm-shims/support/com/yefeng/majmax/hookprobe/manager/AssistLaunch.kt" \
  "$PROJ/tools/tests/jvm-shims/support/com/yefeng/majmax/hookprobe/GameHintLayer.kt"

echo "== 3/5 real control sources on top of the shim =="
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
CLASSPATH_PROD="$OUT/kotlin:$OUT/shim:$STDLIB"
[ -n "$R_JAR" ] && [ -f "$R_JAR" ] && CLASSPATH_PROD="$CLASSPATH_PROD:$R_JAR"
[ -n "$JSON" ] && CLASSPATH_PROD="$CLASSPATH_PROD:$JSON"
"$JAVAC" -nowarn -source 21 -target 21 -encoding UTF-8 -proc:none \
  -d "$OUT/classes" -cp "$CLASSPATH_PROD" \
  "$SRC/GameTopControl.java" "$SRC/GameTopUi.java" \
  "$SRC/GameDisplayGate.java" "$SRC/HookDiagnostics.java"

echo "== 4/5 probe =="
rm -rf "$OUT/probe" && mkdir -p "$OUT/probe/src"
cp "$PROJ/tools/tests/TopControlLayoutProbe.kt" "$OUT/probe/src/"
"$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$OUT/classes:$CLASSPATH_PROD" \
  -jvm-target 21 -nowarn -d "$OUT/probe" \
  "$OUT/probe/src/TopControlLayoutProbe.kt"

echo "== 5/5 run =="
"$JAVA" -cp "$OUT/probe:$OUT/classes:$CLASSPATH_PROD" TopControlLayoutProbeKt
