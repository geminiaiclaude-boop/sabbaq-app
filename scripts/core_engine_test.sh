#!/usr/bin/env bash
# Compiles core/engine + its tests with the cached Kotlin compiler (no Gradle daemon) and runs JUnit.
set -e
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
LIBS="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-core-jvm-1.8.1.jar'):$(jar junit/junit/4.13.2 '*.jar'):$(jar org.hamcrest/hamcrest-core/1.3 '*.jar')"
OUT=$(mktemp -d)
cd "$(dirname "$0")/.."
SRC="core/src/main/kotlin/app/sabbaq/core/Model.kt $(ls core/src/main/kotlin/app/sabbaq/core/engine/*.kt) $(ls core/src/test/kotlin/app/sabbaq/core/engine/*.kt)"
"$J" -Xmx700m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$(cygpath -wp "$LIBS")" -d "$(cygpath -w $OUT)" $SRC 2>&1 | grep -v "^warning" || true
TESTS=$(cd $OUT && ls app/sabbaq/core/engine/*Test.class | sed 's/\.class$//; s#/#.#g')
"$J" -Xmx300m -cp "$(cygpath -wp "$OUT:$LIBS")" org.junit.runner.JUnitCore $TESTS
