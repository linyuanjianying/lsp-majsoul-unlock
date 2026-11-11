#!/bin/sh
# JVM pure-logic regression for the floating window's colour maths + shape machine.
#
# Compiles ONLY the probe (plus a tiny `android.graphics.Color` shim: the SDK `android.jar`
# methods are `throw new RuntimeException("Stub!")`, so colour assertions need a real
# implementation of argb/rgb/alpha/red/green/blue) against the project's compiled Kotlin
# classes. No project source is modified.
set -eu
PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VARIANT=${VARIANT:-arm64Debug}
CLASSES=${CLASSES:-"$PROJ/hook-probe/build/tmp/kotlin-classes/$VARIANT"}
OUT="$PROJ/hook-probe/build/floating-window-probe"
ANDROID_JAR=${ANDROID_HOME:-/workspace/android-sdk}/platforms/android-35/android.jar
GC=/root/.gradle/caches/modules-2/files-2.1
find_jar() { find "$1" -name "$2" ! -name '*sources*' | head -1; }

KOTLINC=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-*.jar')
STDLIB=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-2.*.jar')
JSON=$(find_jar "$GC/org.json/json" 'json-*.jar')
if [ -z "$KOTLINC" ] || [ -z "$STDLIB" ] || [ -z "$JSON" ]; then
  echo "missing kotlin/json jars under $GC" >&2
  exit 2
fi
if [ ! -d "$CLASSES" ]; then
  echo "compiled classes not found at $CLASSES (build the arm64Debug variant first, or set CLASSES=)" >&2
  exit 3
fi
KCP="$OUT/compiler-classpath.txt"
mkdir -p "$OUT"
[ -s "$KCP" ] || find "$GC/org.jetbrains.kotlin" "$GC/org.jetbrains.intellij.deps" "$GC/org.jetbrains" \
  "$GC/trove4j" "$GC/org.jetbrains.kotlinx" "$GC/org.jetbrains.gradle" \
  -type f -name '*.jar' ! -name '*sources*' 2>/dev/null | tr '\n' ':' > "$KCP"

JAVA=/usr/lib/jvm/java-21-openjdk-arm64/bin/java
[ -x "$JAVA" ] || JAVA=java

rm -rf "$OUT/classes"
"$JAVA" -cp "$KOTLINC:$(cat "$KCP")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$CLASSES:$STDLIB:$JSON:$ANDROID_JAR" \
  -Xfriend-paths="$CLASSES" -jvm-target 21 -nowarn \
  -d "$OUT/classes" "$PROJ/tools/tests/jvm-shims/AndroidColor.kt" \
  "$PROJ/tools/tests/FloatingWindowProbe.kt"
# Shim classes come first so `android.graphics.Color` resolves to the real implementation
# instead of the throwing SDK stub; everything else still comes from the project classes.
"$JAVA" -cp "$OUT/classes:$CLASSES:$STDLIB:$JSON:$ANDROID_JAR" FloatingWindowProbeKt
