#!/usr/bin/env bash
# Compiles the whole app module (app/src/main/java + core) with the Compose and serialization
# plugins against the cached libraries, no Gradle. R and BuildConfig are generated from the
# names the code uses, so a missing resource is NOT caught here (aapt does that in CI).
# Prints compiler errors, then the number of classes produced. The cache holds appcompat 1.2.0, so
# LocaleManager.kt (setApplicationLocales, appcompat 1.6+) is the one expected failure here.
set +e
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
cd "$(dirname "$0")/.."
SDK=$(grep sdk.dir local.properties | cut -d= -f2)
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
SER="$(jar org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.21 '*.jar')"
COMPOSE="$(jar org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.21 '*.jar')"
T=$(mktemp -d)
LIBS=("$(cygpath -u "$SDK")/platforms/android-36/android.jar")
for dir in $(ls -d $C/androidx.* $C/org.maplibre.gl $C/org.jetbrains.kotlinx $C/org.jetbrains.kotlin $C/com.squareup.okhttp3 $C/com.squareup.okio $C/com.jakewharton.timber $C/com.google.android.libraries.identity.googleid 2>/dev/null); do
  for art in $(ls $dir 2>/dev/null); do
    [ -d "$dir/$art" ] || continue
    case "$art" in *compiler*|*gradle*|*daemon*|*scripting*|*build-tools*|*-test*|*junit*) continue ;; esac
    f=""
    for v in $(ls $dir/$art | sort -V -r); do
      f=$(find $dir/$art/$v \( -name "*.aar" -o -name "*.jar" \) | grep -v "sources\|javadoc" | head -1)
      [ -n "$f" ] && break
    done
    [ -n "$f" ] || continue
    case "$f" in
      *.aar) d="$T/lib/$(basename $dir)/$art"; mkdir -p "$d"; unzip -q -o -d "$d" "$f" classes.jar 2>/dev/null && [ -f "$d/classes.jar" ] && LIBS+=("$d/classes.jar") ;;
      *.jar) LIBS+=("$f") ;;
    esac
  done
done
mkdir -p "$T/stub"
{
  echo 'package app.sabbaq'
  echo 'object R {'
  for kind in $(grep -ho "R\.[a-z]*\.[A-Za-z_0-9]*" -r app/src/main/java | cut -d. -f2 | sort -u); do
    echo "object $kind {"
    grep -ho "R\.$kind\.[A-Za-z_0-9]*" -r app/src/main/java | cut -d. -f3 | sort -u | sed 's/^/const val /; s/$/ = 0/'
    echo '}'
  done
  echo '}'
  echo 'object BuildConfig { const val APPLICATION_ID = "app.sabbaq.driver"; const val DIRECT_DISTRIBUTION = true; const val GOOGLE_WEB_CLIENT_ID = ""'
  echo '  const val SUPABASE_ANON_KEY = ""; const val SUPABASE_URL = ""; const val VERSION_CODE = 1; const val VERSION_NAME = "check"; const val DEBUG = true }'
} > "$T/stub/Generated.kt"
OUT=$(mktemp -d)
echo "libs: ${#LIBS[@]}" >&2; CPW=""
for l in "${LIBS[@]}"; do CPW="$CPW;$(cygpath -m "$l")"; done
ARGS="$T/args.txt"
{
  echo "-no-stdlib"; echo "-no-reflect"; echo "-jvm-target"; echo "17"
  echo "-Xplugin=$(cygpath -m "$SER")"; echo "-Xplugin=$(cygpath -m "$COMPOSE")"
  echo "-cp"; echo "${CPW:1}"
  echo "-d"; echo "$(cygpath -m "$OUT")"
  find core/src/main/kotlin app/src/main/java -name '*.kt' | while read -r f; do cygpath -m "$f"; done
  cygpath -m "$T/stub/Generated.kt"
} > "$ARGS"
"$J" -Xmx1200m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$(cygpath -m "$ARGS")" 2>&1 | grep -v "^warning" || true
find $OUT -name "*.class" | wc -l
