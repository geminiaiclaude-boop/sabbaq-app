#!/usr/bin/env bash
# Compiles the whole core module and its tests with the cached Kotlin compiler (no Gradle daemon,
# serialization plugin included) and runs every JUnit test class. Usage: scripts/core_engine_test.sh
set -e
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
PLUGIN="$(jar org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.21 '*.jar')"
LIBS="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-core-jvm-1.8.1.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-json-jvm-1.8.1.jar'):$(jar junit/junit/4.13.2 '*.jar'):$(jar org.hamcrest/hamcrest-core/1.3 '*.jar')"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
cd "$(dirname "$0")/.."
SRC=$(find core/src/main/kotlin core/src/test/kotlin -name '*.kt' | tr '\n' ' ')
"$J" -Xmx900m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -Xplugin="$(cygpath -w "$PLUGIN")" -cp "$(cygpath -wp "$LIBS")" -d "$(cygpath -w $OUT)" $SRC > "$OUT.log" 2>&1 || { grep -v "^warning" "$OUT.log"; rm -f "$OUT.log"; echo "core compile failed" >&2; exit 1; }
grep -v "^warning" "$OUT.log" || true
rm -f "$OUT.log"
cp -r core/src/main/resources/. $OUT/ 2>/dev/null || true
TESTS=$(cd $OUT && find app -name '*Test.class' | sed 's/\.class$//; s#/#.#g' | tr '\n' ' ')
"$J" -Xmx400m -cp "$(cygpath -wp "$OUT:$LIBS")" org.junit.runner.JUnitCore $TESTS
