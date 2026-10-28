#!/usr/bin/env bash
# Type-checks app/service/engine against android.jar and the core engine (no Gradle).
set -e
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
SDK=$(grep sdk.dir local.properties | cut -d= -f2)
LIBS="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-core-jvm-1.8.1.jar'):$(cygpath -u "$SDK")/platforms/android-36/android.jar"
OUT=$(mktemp -d)
SRC="core/src/main/kotlin/app/sabbaq/core/Model.kt $(ls core/src/main/kotlin/app/sabbaq/core/engine/*.kt) $(ls app/src/main/java/app/sabbaq/service/engine/*.kt)"
"$J" -Xmx700m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$(cygpath -wp "$LIBS")" -d "$(cygpath -w $OUT)" $SRC 2>&1 | grep -v "^warning" || true
ls $OUT/app/sabbaq/service/engine/ 2>/dev/null | head -3
