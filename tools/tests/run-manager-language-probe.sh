#!/bin/sh
# JVM pure-logic regression for the manager language switch (ManagerLanguage.kt +
# UiLanguage.kt + AiScreen.kt).
#
# Compiles the real sources against the already-built arm64Debug Kotlin classes
# (-Xfriend-paths exposes `internal`) and runs main(); only pure functions are called, so no
# Context, composition or native library is touched.
#
# This script does NOT run Gradle: it consumes the classes produced by
#   (cd hook-probe && ../gradlew :compileArm64DebugKotlin)
# and is therefore safe to run while the main integrator owns the build.
#
# Usage: tools/tests/run-manager-language-probe.sh
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$PROJ/hook-probe/src/main/java/com/yefeng/majmax/hookprobe/manager"
CLASSES="$PROJ/hook-probe/build/tmp/kotlin-classes/arm64Debug"
OUT="${TMPDIR:-/tmp}/manager-language-probe"
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=${GRADLE_USER_HOME:-/root/.gradle}/caches/modules-2/files-2.1

JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

# Pin the versions the project builds with: a newer cached stdlib makes the compiler accept
# code it cannot link, and the probe would then test something other than the shipped build.
KOTLIN_VERSION=2.0.21
COROUTINES_VERSION=1.10.2

jar() {
  dir="$GC/$1/$2"
  [ -d "$dir" ] || { echo "missing artifact: $1:$2" >&2; exit 2; }
  found=$(find "$dir" -type f -name "$3" ! -name '*sources*' 2>/dev/null | sort | tail -1)
  [ -n "$found" ] || { echo "missing jar $1:$2:$3" >&2; exit 2; }
  printf '%s' "$found"
}

KOTLINC=$(jar org.jetbrains.kotlin kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar")
STDLIB=$(jar org.jetbrains.kotlin kotlin-stdlib "kotlin-stdlib-$KOTLIN_VERSION.jar")
REFLECT=$(jar org.jetbrains.kotlin kotlin-reflect "kotlin-reflect-2.0.20.jar" || true)
SCRIPT_RUNTIME=$(jar org.jetbrains.kotlin kotlin-script-runtime "kotlin-script-runtime-$KOTLIN_VERSION.jar")
DAEMON=$(jar org.jetbrains.kotlin kotlin-daemon-embeddable "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar")
TROVE=$(jar org.jetbrains.intellij.deps trove4j "trove4j-*.jar")
ANNOTATIONS=$(jar org.jetbrains annotations "annotations-*.jar")
COROUTINES=$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm "kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar")
ORGJSON=$(jar org.json json "json-*.jar")

[ -d "$CLASSES" ] || {
  echo "missing $CLASSES; run: (cd hook-probe && ../gradlew :compileArm64DebugKotlin)" >&2
  exit 2
}

# ManagerLanguage.kt itself needs Compose's MutableState: the runtime artifact ships as an AAR,
# so its classes.jar is unpacked once next to the scratch output.
COMPOSE_AAR=$(find "$GC/androidx.compose.runtime/runtime-android" -name '*.aar' ! -name '*sources*' \
  -path '*/1.7.6/*' 2>/dev/null | head -1)
[ -n "$COMPOSE_AAR" ] || COMPOSE_AAR=$(find "$GC/androidx.compose.runtime/runtime-android" -name '*.aar' 2>/dev/null | head -1)
COMPOSE_JAR=""
if [ -n "$COMPOSE_AAR" ]; then
  COMPOSE_JAR="$OUT/compose-runtime-classes.jar"
  if [ ! -s "$COMPOSE_JAR" ]; then
    rm -rf "$OUT/compose-runtime" && mkdir -p "$OUT/compose-runtime"
    (cd "$OUT/compose-runtime" && unzip -o -q "$COMPOSE_AAR" classes.jar 2>/dev/null) &&
      [ -s "$OUT/compose-runtime/classes.jar" ] && mv "$OUT/compose-runtime/classes.jar" "$COMPOSE_JAR"
  fi
fi

COMPILER_CP="$KOTLINC:$STDLIB:$REFLECT:$SCRIPT_RUNTIME:$DAEMON:$COROUTINES:$TROVE:$ANNOTATIONS"
PROBE_CP="$CLASSES:$STDLIB:$COROUTINES:$ORGJSON:$ANDROID_JAR:$COMPOSE_JAR"

mkdir -p "$OUT"
rm -rf "$OUT/classes"

# Only modules that do not need a native library are listed: OnnxModelStore/AiNative are
# replaced by the inert shims (they are never executed by the probe).
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$PROBE_CP" \
  -Xfriend-paths="$CLASSES" -jvm-target 21 -nowarn \
  -d "$OUT/classes" \
  "$SRC/ManagerLanguage.kt" \
  "$SRC/UiLanguage.kt" \
  "$PROJ/tools/tests/jvm-shims/OnnxModelStoreShim.kt" \
  "$PROJ/tools/tests/jvm-shims/AiNativeShim.kt" \
  "$PROJ/tools/tests/ManagerLanguageProbe.kt"

set +e
"$JAVA" -cp "$OUT/classes:$PROBE_CP" ManagerLanguageProbeKt
STATUS=$?
set -e
if [ "$STATUS" -ne 0 ]; then
  echo "manager-language probe FAILED (exit $STATUS)" >&2
  exit "$STATUS"
fi
