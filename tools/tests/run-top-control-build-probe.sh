#!/bin/sh
# JVM build-regression for the in-game top control (GameTopControl.java / GameTopUi.java).
#
# It runs the REAL control sources on a plain JVM: the android.* classes come from
# tools/tests/jvm-shims/android-shim, which keeps view tags, children, listeners and
# performClick() faithful and stubs only drawing, measuring and resources. That is enough
# to rebuild the whole control page and prove its wiring, which is where the v0.15 dark-blue
# preview crashed the game:
#
#   NPE  GameTopUi$Glyph.setOnClickListener(...) on a null object reference
#        at GameTopControl.buildControlPage(GameTopControl.java:1065)
#
# No device, no Gradle, no aapt: javac + the Kotlin compiler against the app's own sources.
# The Kotlin half (InGameLocalizer and the real manager/OpponentNaming model of the naming
# card, plus reduced stand-ins for the manager's language and copy tables) is compiled from
# source as well, so a Gradle build that is wiping
# hook-probe/build at the same time cannot break this probe. Nothing is written under
# hook-probe/build: the output goes to /tmp/top-control-build-probe (override with
# TOP_BUILD_PROBE_OUT).
#
# Usage: tools/tests/run-top-control-build-probe.sh
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$PROJ/hook-probe/src/main/java/com/yefeng/majmax/hookprobe"
SHIM="$PROJ/tools/tests/jvm-shims/android-shim"
# The Kotlin half of the module is compiled here from source, so the probe never depends on
# a Gradle build that another process may be running (it empties build/tmp/kotlin-classes).
KOTLIN_SRC="$(dirname "$(find "$SRC/helper" -name 'InGameLocalizer.kt' | head -1)")"
# The generated R class is referenced through the resource pipeline only; it is on the
# normal javac classpath of the app build, so it is here too.
R_JAR="$PROJ/hook-probe/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/arm64Debug/processArm64DebugResources/R.jar"
# Deliberately outside hook-probe/build: another build may be running at the same time and
# Gradle owns (and wipes) everything under build/. Override with TOP_BUILD_PROBE_OUT.
OUT=${TOP_BUILD_PROBE_OUT:-/tmp/top-control-build-probe}
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
# org.json is an android.jar class on the device; the JVM needs the real library.
JSON=$(find_jar "$GC/org.json/json" 'json-*.jar')

mkdir -p "$OUT"
KCP="$OUT/compiler-classpath.txt"
[ -s "$KCP" ] || find "$GC/org.jetbrains.kotlin" "$GC/org.jetbrains.intellij.deps" "$GC/org.jetbrains" \
  "$GC/trove4j" "$GC/org.jetbrains.kotlinx" "$GC/org.jetbrains.gradle" "$GC/org.json" \
  -type f -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':' > "$KCP"

echo "== 1/5 shim =="
rm -rf "$OUT/shim" && mkdir -p "$OUT/shim"
"$JAVAC" -nowarn -source 21 -target 21 -d "$OUT/shim" $(find "$SHIM" -name '*.java')

# The Java side calls into the Kotlin side (this::inGameVisible into GameHintLayer), so the
# Kotlin stdlib must be on the javac classpath as well, not only at run time.
echo "== 2/5 real InGameLocalizer + minimal manager collaborators =="
rm -rf "$OUT/kotlin" && mkdir -p "$OUT/kotlin"
"$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$OUT/shim:$STDLIB:$ANDROID_JAR${JSON:+:$JSON}" \
  -jvm-target 21 -nowarn -d "$OUT/kotlin" \
  "$SRC/helper/InGameLocalizer.kt" \
  "$SRC/manager/OpponentNaming.kt" \
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
  "$SRC/GameTopControl.java" "$SRC/GameTopUi.java" "$SRC/GameTopDesign.java" \
  "$SRC/GameDisplayGate.java" "$SRC/HookDiagnostics.java"

echo "== 4/5 probe =="
rm -rf "$OUT/probe" && mkdir -p "$OUT/probe"
"$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$OUT/classes:$CLASSPATH_PROD" \
  -jvm-target 21 -nowarn -d "$OUT/probe" \
  "$PROJ/tools/tests/TopControlBuildProbe.kt"

echo "== 5/5 run =="
"$JAVA" -cp "$OUT/probe:$OUT/classes:$CLASSPATH_PROD" TopControlBuildProbeKt
