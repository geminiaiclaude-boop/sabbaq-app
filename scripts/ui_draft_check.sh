#!/usr/bin/env bash
# Compiles the conditions draft + reason labels against the core engine (LocaleManager stubbed) and runs ConditionsDraftTest.
set -e
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
PLUGIN="$(jar org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.21 '*.jar')"
LIBS="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-core-jvm-1.8.1.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-serialization-json-jvm-1.8.1.jar'):$(jar junit/junit/4.13.2 '*.jar'):$(jar org.hamcrest/hamcrest-core/1.3 '*.jar')"
OUT=$(mktemp -d); STUB=$(mktemp -d)
printf 'package app.sabbaq\nobject LocaleManager { fun isArabic(): Boolean = true }\n' > $STUB/LocaleManager.kt
cd "$(dirname "$0")/.."
"$J" -Xmx800m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Xplugin="$(cygpath -w "$PLUGIN")" \
  -cp "$(cygpath -wp "$LIBS")" -d "$(cygpath -w $OUT)" core/src/main/kotlin/app/sabbaq/core/*.kt core/src/main/kotlin/app/sabbaq/core/engine/*.kt core/src/main/kotlin/app/sabbaq/core/engine/zones/*.kt \
  app/src/main/java/app/sabbaq/ui/FilterDraft.kt app/src/main/java/app/sabbaq/ui/ReasonLabels.kt app/src/test/java/app/sabbaq/ui/ConditionsDraftTest.kt $STUB/LocaleManager.kt 2>&1 | grep -v "^warning" || true
"$J" -cp "$(cygpath -wp "$OUT:$LIBS")" org.junit.runner.JUnitCore app.sabbaq.ui.ConditionsDraftTest
