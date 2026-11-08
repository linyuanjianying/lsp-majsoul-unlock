#!/bin/sh
# JVM pure-logic regression for TileBadgeOverlay / CatHintView.
# Read-only w.r.t. project sources: compiles only the probe file against the
# already-built universalDebug kotlin classes (-Xfriend-paths exposes `internal`).
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
CLASSES="$PROJ/hook-probe/build/tmp/kotlin-classes/universalDebug"
OUT="$PROJ/hook-probe/build/hint-regression"
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=/root/.gradle/caches/modules-2/files-2.1
find_jar() { find "$1" -name "$2" ! -name '*sources*' | head -1; }

KOTLINC=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-*.jar')
STDLIB=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-2.*.jar')
JSON=$(find_jar "$GC/org.json/json" 'json-*.jar')
COROUTINES=$(find_jar "$GC/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
KCP="$OUT/compiler-classpath.txt"
mkdir -p "$OUT"
[ -s "$KCP" ] || find "$GC/org.jetbrains.kotlin" "$GC/org.jetbrains.intellij.deps" "$GC/org.jetbrains" \
  "$GC/trove4j" "$GC/org.jetbrains.kotlinx" "$GC/org.jetbrains.gradle" \
  -type f -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':' > "$KCP"

JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

run_kotlinc() {
  "$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -classpath "$CLASSES:$STDLIB:$JSON:$ANDROID_JAR:$COROUTINES" \
    -Xfriend-paths="$CLASSES" -jvm-target 21 -nowarn \
    -d "$OUT/classes" "$PROJ/tools/tests/JvmPureLogicProbe.kt"
}

rm -rf "$OUT/classes"
run_kotlinc
"$JAVA" -cp "$OUT/classes:$CLASSES:$STDLIB:$JSON:$ANDROID_JAR:$COROUTINES" JvmPureLogicProbeKt
