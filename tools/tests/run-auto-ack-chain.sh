#!/bin/sh
# JVM pure-logic regression for the autoplay ACK chain and its diagnostics.
#
# Compiles the production sources it exercises (AutoAckDetails, AutoDiscardController,
# AutoDelaySettings) together with tools/tests/jvm-shims, so it
# does not depend on a previous Gradle run: the shims replace the android.jar stubs
# and the coroutines factory a plain `java` launcher cannot load.
#
# A device is not involved and no game action is ever performed: the clock is
# injected, the adapter rows are literal JSON copied from the Lua emitters, and the
# controller is driven exactly as the service drives it (observe / poll / pause).
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$PROJ/hook-probe/src/main/java/com/yefeng/majmax/hookprobe/manager"
OUT="$PROJ/hook-probe/build/auto-ack-chain"
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=${GRADLE_CACHE:-/root/.gradle/caches/modules-2/files-2.1}
KOTLIN_VERSION=2.0.21

JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

# Newest file matching a pattern inside one artifact version directory.
jar() {
  dir="$GC/$1/$2"
  [ -d "$dir" ] || { echo "missing artifact: $1:$2" >&2; exit 2; }
  found=$(find "$dir" -type f -name "$3" ! -name '*sources*' 2>/dev/null | sort | tail -1)
  [ -n "$found" ] || { echo "missing jar $1:$2:$3" >&2; exit 2; }
  printf '%s' "$found"
}

# Every artifact is pinned to the version the project builds with: a newer cached
# kotlin-stdlib/compiler makes the compiler accept code it cannot link, and the probe
# would then exercise a differently-compiled controller.
KOTLINC=$(jar org.jetbrains.kotlin kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar")
STDLIB=$(jar org.jetbrains.kotlin kotlin-stdlib "kotlin-stdlib-$KOTLIN_VERSION.jar")
REFLECT=$(jar org.jetbrains.kotlin kotlin-reflect "kotlin-reflect-2.0.20.jar")
SCRIPT_RUNTIME=$(jar org.jetbrains.kotlin kotlin-script-runtime "kotlin-script-runtime-$KOTLIN_VERSION.jar")
DAEMON=$(jar org.jetbrains.kotlin kotlin-daemon-embeddable "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar")
COROUTINES=$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm "kotlinx-coroutines-core-jvm-1.10.2.jar")
TROVE=$(jar org.jetbrains.intellij.deps trove4j "trove4j-*.jar")
ANNOTATIONS=$(jar org.jetbrains annotations "annotations-*.jar")
[ -f "$ANDROID_JAR" ] || { echo "missing android.jar: $ANDROID_JAR" >&2; exit 2; }

COMPILER_CP="$KOTLINC:$STDLIB:$REFLECT:$SCRIPT_RUNTIME:$DAEMON:$COROUTINES:$TROVE:$ANNOTATIONS"
# The sources only need the stdlib, the coroutines Flow types and android.jar (for
# Context/SystemClock references inside AutoDelaySettings).
COMPILE_CP="$STDLIB:$COROUTINES:$ANDROID_JAR"

rm -rf "$OUT"
mkdir -p "$OUT/classes"
echo "compiler: $KOTLINC" >&2
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$COMPILE_CP" \
  -jvm-target 21 -nowarn \
  -d "$OUT/classes" \
  "$PROJ/tools/tests/jvm-shims/SystemClock.kt" \
  "$PROJ/tools/tests/jvm-shims/FlowShim.kt" \
  "$PROJ/tools/tests/jvm-shims/OrgJson.kt" \
  "$SRC/AutoAckDetails.kt" \
  "$SRC/AutoDiscardController.kt" \
  "$SRC/AutoDelaySettings.kt" \
  "$PROJ/tools/tests/AutoAckChainProbe.kt"

# The shims sort first: they replace android.jar's stubs and the coroutines factory.
"$JAVA" -cp "$OUT/classes:$STDLIB:$COROUTINES:$ANDROID_JAR" com.yefeng.majmax.hookprobe.manager.AutoAckChainProbeKt
