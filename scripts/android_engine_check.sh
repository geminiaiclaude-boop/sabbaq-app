#!/usr/bin/env bash
# Type-checks app/service/engine and app/service/zones against android.jar, androidx core and the core engine (no Gradle).
set -e
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
SDK=$(grep sdk.dir local.properties | cut -d= -f2)
PLUGIN="$(jar org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.21 '*.jar')"
STUB=$(mktemp -d)
unzip -q -o -d "$STUB/androidx-core" "$(jar androidx.core/core/1.16.0 'core-1.16.0.aar')" classes.jar
printf 'package app.sabbaq\nobject BuildConfig { const val VERSION_NAME = "check" }\n' > "$STUB/BuildConfig.kt"
LIBS="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-core-jvm-1.8.1.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-json-jvm-1.8.1.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$STUB/androidx-core/classes.jar:$(cygpath -u "$SDK")/platforms/android-36/android.jar"
OUT=$(mktemp -d)
SRC="core/src/main/kotlin/app/sabbaq/core/Model.kt $(ls core/src/main/kotlin/app/sabbaq/core/engine/*.kt core/src/main/kotlin/app/sabbaq/core/engine/zones/*.kt) $(ls app/src/main/java/app/sabbaq/service/engine/*.kt app/src/main/java/app/sabbaq/service/zones/*.kt) $STUB/BuildConfig.kt"
"$J" -Xmx700m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Xplugin="$(cygpath -w "$PLUGIN")" \
  -cp "$(cygpath -wp "$LIBS")" -d "$(cygpath -w $OUT)" $SRC 2>&1 | grep -v "^warning" || true
ls $OUT/app/sabbaq/service/engine/ $OUT/app/sabbaq/service/zones/ 2>/dev/null | grep -c class
