#!/bin/sh
# JVM pure-logic regression for the in-game / floating-window copy
# (GameMenuText.kt + InGameLocalizer.kt + RecommendationPanelOverlay.kt).
#
# Read-only w.r.t. the project: it compiles the real Kotlin sources together with two probes
# against the already-built arm64Debug classes (-Xfriend-paths exposes `internal`) and only
# calls pure functions, so no Context, window, service or provider is created:
#
#   InGameCopyProbe.kt   the translation tables, the panel lines, the machine values
#   InGameStateProbe.kt  the language state machine the menu and the panel follow
#
# The built classes are stale after a source edit, so the files this probe covers are compiled
# from source into a private output directory and put *first* on the classpath; every other
# class still comes from the last build. This never runs Gradle: build the app normally to
# refresh the rest.
#
# Usage: tools/tests/run-in-game-copy-probe.sh
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
CLASSES="$PROJ/hook-probe/build/tmp/kotlin-classes/arm64Debug"
SRC="$PROJ/hook-probe/src/main/java/com/yefeng/majmax/hookprobe"
JAVAC_CLASSES="$PROJ/hook-probe/build/intermediates/javac/arm64Debug/compileArm64DebugJavaWithJavac/classes"
OUT="$PROJ/hook-probe/build/in-game-copy-probe"
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=${GRADLE_USER_HOME:-/root/.gradle}/caches/modules-2/files-2.1
find_jar() { find "$1" -name "$2" ! -name '*sources*' | head -1; }

[ -d "$CLASSES" ] || { echo "missing $CLASSES; build the app once first" >&2; exit 2; }
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR; set ANDROID_HOME" >&2; exit 2; }

KOTLINC=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-*.jar')
STDLIB=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-2.*.jar')
JSON=$(find_jar "$GC/org.json/json" 'json-*.jar')
COROUTINES=$(find_jar "$GC/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" 'kotlinx-coroutines-core-jvm-*.jar')
COMPOSE_AAR=$(find "$GC/androidx.compose.runtime/runtime-android" -name '*.aar' ! -name '*sources*' 2>/dev/null | head -1)
[ -n "$KOTLINC" ] || { echo "missing kotlin-compiler-embeddable in $GC" >&2; exit 2; }
[ -n "$STDLIB" ] || { echo "missing kotlin-stdlib in $GC" >&2; exit 2; }
[ -n "$JSON" ] || { echo "missing org.json in $GC" >&2; exit 2; }

KCP="$OUT/compiler-classpath.txt"
mkdir -p "$OUT"
[ -s "$KCP" ] || find "$GC/org.jetbrains.kotlin" "$GC/org.jetbrains.intellij.deps" "$GC/org.jetbrains" \
  "$GC/trove4j" "$GC/org.jetbrains.kotlinx" "$GC/org.jetbrains.gradle" "$GC/org.json" \
  -type f -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':' > "$KCP"

# Compose runtime ships as an AAR; its classes.jar is unpacked once for the classpath. It is
# only needed by ManagerLanguage.kt (the Compose screens reuse the already-built classes).
COMPOSE_JAR=""
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

JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

BASE_CP="$JAVAC_CLASSES:$CLASSES:$STDLIB:$JSON:$ANDROID_JAR${COROUTINES:+:$COROUTINES}${COMPOSE_JAR:+:$COMPOSE_JAR}"
KOTLINC_CP="$KOTLINC:$(cat "$KCP")"
FRIENDS="$CLASSES"

compile() {
  # compile <out-dir> <extra-classpath> <sources...>
  out=$1; extra=$2; shift 2
  "$JAVA" -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -classpath "$extra:$BASE_CP" \
    -Xfriend-paths="$FRIENDS" -jvm-target 21 -nowarn -d "$out" "$@"
}

rm -rf "$OUT/ml" "$OUT/sources" "$OUT/panel" "$OUT/probe" "$OUT/state"
mkdir -p "$OUT/ml" "$OUT/sources" "$OUT/panel" "$OUT/state"

# 1. ManagerLanguage.kt + UiLanguage.kt, the shared vocabulary and the public API the
#    provider answers with (needs the Compose runtime artifact).
compile "$OUT/ml" "" "$SRC/manager/ManagerLanguage.kt" "$SRC/manager/UiLanguage.kt"
FRIENDS="$FRIENDS,$OUT/ml"

# 2. This change's helpers.
compile "$OUT/sources" "$OUT/ml" "$SRC/helper/GameMenuText.kt" "$SRC/helper/InGameLocalizer.kt"
FRIENDS="$FRIENDS,$OUT/sources"

# 3. The recommendation panel (its lines are what both probes assert on).
compile "$OUT/panel" "$OUT/ml:$OUT/sources" "$SRC/manager/RecommendationPanelOverlay.kt"
FRIENDS="$FRIENDS,$OUT/panel"

PROBE_CP="$OUT/panel:$OUT/sources:$OUT/ml:$BASE_CP"

compile "$OUT/probe" "$PROBE_CP" "$PROJ/tools/tests/InGameCopyProbe.kt"
"$JAVA" -cp "$OUT/probe:$PROBE_CP" InGameCopyProbeKt

compile "$OUT/state" "$PROBE_CP" "$PROJ/tools/tests/InGameStateProbe.kt"
"$JAVA" -cp "$OUT/state:$PROBE_CP" InGameStateProbeKt
