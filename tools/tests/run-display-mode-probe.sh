#!/bin/sh
# JVM pure-logic regression for the single-surface display mode (overlay vs in-game hook).
# Read-only w.r.t. project sources: compiles only the probe file against the already
# built universalDebug kotlin classes (-Xfriend-paths exposes `internal`).
#
# Build the classes first:  (cd hook-probe && ./gradlew :compileUniversalDebugKotlin)
# The same cases exist as instrumentation checks (DisplayModeChecks), but this runs on a
# plain JVM, so the "only one surface is ever created" orders can be replayed without a
# device, a window or the game process.
#
# Every artifact is pinned to the version the project builds with. A newer cached
# kotlin-stdlib/compiler on the classpath makes the compiler accept code it cannot link,
# and the probe then exercises differently-compiled mode code — the exact trap that made an
# earlier run report results of a stale build.
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
# Class directory to compile against. `KOTLIN_CLASSES` lets a caller point at a fresh
# standalone :main compile (see the note in the header) when the Gradle task is
# unavailable in this build environment.
CLASSES="${KOTLIN_CLASSES:-$PROJ/hook-probe/build/tmp/kotlin-classes/universalDebug}"
OUT="$PROJ/hook-probe/build/display-mode-probe"
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=${GRADLE_CACHE:-/root/.gradle/caches/modules-2/files-2.1}
KOTLIN_VERSION=2.0.21
COROUTINES_VERSION=1.10.2

JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

# Newest file matching a pattern inside one artifact version directory. The second
# argument may be an exact version, a spaced list of versions, or "*" for any.
jar() {
  dir="$GC/$1/$2"
  [ -d "$dir" ] || { echo "missing artifact: $1:$2" >&2; exit 2; }
  found=$(find "$dir" -type f -name "$3" ! -name '*sources*' 2>/dev/null | sort | tail -1)
  [ -n "$found" ] || { echo "missing jar $1:$2:$3" >&2; exit 2; }
  printf '%s' "$found"
}

KOTLINC=$(jar org.jetbrains.kotlin kotlin-compiler-embeddable "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar")
STDLIB=$(jar org.jetbrains.kotlin kotlin-stdlib "kotlin-stdlib-$KOTLIN_VERSION.jar")
# No kotlin-reflect for 2.0.21 is cached; the compiler only needs a 2.0.x reflect.
REFLECT=$(jar org.jetbrains.kotlin kotlin-reflect "kotlin-reflect-2.0.20.jar")
SCRIPT_RUNTIME=$(jar org.jetbrains.kotlin kotlin-script-runtime "kotlin-script-runtime-$KOTLIN_VERSION.jar")
DAEMON=$(jar org.jetbrains.kotlin kotlin-daemon-embeddable "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar")
COROUTINES=$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm "kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar")
TROVE=$(jar org.jetbrains.intellij.deps trove4j "trove4j-*.jar")
ANNOTATIONS=$(jar org.jetbrains annotations "annotations-*.jar")
# org.json is on DiagnosticsStore's compile classpath (exportFileName lives there).
ORGJSON=$(jar org.json json "json-*.jar")
[ -d "$CLASSES" ] || { echo "missing build output: $CLASSES (run :compileUniversalDebugKotlin)" >&2; exit 2; }

# The embeddable compiler needs these on *its* classpath.
COMPILER_CP="$KOTLINC:$STDLIB:$REFLECT:$SCRIPT_RUNTIME:$DAEMON:$COROUTINES:$TROVE:$ANNOTATIONS"
PROBE_CP="$CLASSES:$STDLIB:$COROUTINES:$ORGJSON:$ANDROID_JAR"

rm -rf "$OUT"
mkdir -p "$OUT/classes"
echo "compiler: $KOTLINC" >&2
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$PROBE_CP" \
  -Xfriend-paths="$CLASSES" -jvm-target 21 -nowarn \
  -d "$OUT/classes" "$PROJ/tools/tests/DisplayModeProbe.kt"
# `-Xskip-metadata-version-check` is needed when $CLASSES comes from a standalone compile
# against a cache that also holds jars built by a newer Kotlin; harmless otherwise.
set +e
"$JAVA" -cp "$OUT/classes:$PROBE_CP" DisplayModeProbeKt
STATUS=$?
set -e
# The probe calls exitProcess(1) on a failed case, so a non-zero status is a regression and
# must not be swallowed by the surrounding pipeline.
if [ "$STATUS" -ne 0 ]; then
  echo "display-mode probe FAILED (exit $STATUS)" >&2
  exit "$STATUS"
fi
