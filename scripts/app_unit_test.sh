#!/usr/bin/env bash
# Runs the app module's JVM unit tests (app/src/test) without Gradle, as CI's
# testDirectDebugUnitTest does: against the real android.jar stubs, so a test that reaches an
# Android API fails here exactly as it fails in CI. Prints JUnit's summary; exit code 1 on failure.
set -u
cd "$(dirname "$0")/.."
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
P=$(mktemp)
EMIT_PATHS="$P" bash scripts/app_compile_check.sh > "$P.log" 2>&1
if grep -q ": error:" "$P.log"; then grep ": error:" "$P.log"; echo "app does not compile"; exit 1; fi
CORE_OUT=$(sed -n 1p "$P"); APP_OUT=$(sed -n 2p "$P"); LIBS=$(sed -n 3p "$P")
JUNIT="$(cygpath -m "$(jar junit/junit/4.13.2 'junit-4.13.2.jar')");$(cygpath -m "$(jar org.hamcrest/hamcrest-core '*.jar')")"
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
SER="$(jar org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.21 '*.jar')"
COMPOSE="$(jar org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.21 '*.jar')"
TEST_OUT=$(mktemp -d)
trap 'rm -rf "$TEST_OUT" "$P" "$P.log" "$P.args"' EXIT
ARGS="$P.args"
{
  echo "-no-stdlib"; echo "-no-reflect"; echo "-jvm-target"; echo "17"; echo "-module-name"; echo "app"
  echo "-Xfriend-paths=$APP_OUT"
  echo "-Xplugin=$(cygpath -m "$SER")"; echo "-Xplugin=$(cygpath -m "$COMPOSE")"
  echo "-cp"; echo "$APP_OUT;$CORE_OUT;$LIBS;$JUNIT"
  echo "-d"; echo "$(cygpath -m "$TEST_OUT")"
  find app/src/test -name '*.kt' | while read -r f; do cygpath -m "$f"; done
} > "$ARGS"
"$J" -Xmx1200m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$(cygpath -m "$ARGS")" 2>&1 | grep -v "^warning" | grep -E "error:|exception" && { echo "tests do not compile"; exit 1; }
CLASSES=$(cd "$TEST_OUT" && find . -name '*Test.class' | grep -v '\$' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
cp -r app/src/test/resources/. "$TEST_OUT"/ 2>/dev/null
RES="$(cygpath -m "$PWD/core/src/main/resources")"
# As Gradle runs them: from the module folder, with the JVM pinned to Arabic (app/build.gradle.kts).
(cd app && "$J" -Xmx700m -Duser.language=ar -Duser.country=SA -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8   -cp "$(cygpath -m "$TEST_OUT");$APP_OUT;$CORE_OUT;$RES;$LIBS;$JUNIT" org.junit.runner.JUnitCore $CLASSES) > "$P.run" 2>&1
STATUS=$?
grep -E "^[0-9]+\) |^Tests run|^OK|^\s+at app\.sabbaq" "$P.run" | head -60
exit $STATUS
