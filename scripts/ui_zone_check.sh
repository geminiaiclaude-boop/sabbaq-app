#!/usr/bin/env bash
# Type-checks ui/ZoneConditions.kt (Compose + osmdroid) against the cached libraries, no Gradle.
# The app's own Compose helpers and R are stubbed with their real signatures.
set -e
C=~/.gradle/caches/modules-2/files-2.1
J=$(ls ~/Desktop/apk-analysis/tools/jre/*/bin/java.exe | head -1)
jar() { find $C/$1 -name "$2" | grep -v sources | head -1; }
cd "$(dirname "$0")/.."
SDK=$(grep sdk.dir local.properties | cut -d= -f2)
CP_C="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.21 '*.jar'):$(jar org.jetbrains.kotlinx 'kotlinx-coroutines-core-jvm-1.10.2.jar'):$(jar org.jetbrains.intellij.deps 'trove4j*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
SER="$(jar org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.1.21 '*.jar')"
COMPOSE="$(jar org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.1.21 '*.jar')"
T=$(mktemp -d)
LIBS="$(cygpath -u "$SDK")/platforms/android-36/android.jar"
for dir in $(ls -d $C/androidx.* $C/org.osmdroid $C/org.jetbrains.kotlinx $C/org.jetbrains.kotlin/kotlin-stdlib); do
  for art in $(ls $dir 2>/dev/null); do
    [ -d "$dir/$art" ] || continue
    v=$(ls $dir/$art | sort -V | tail -1)
    f=$(find $dir/$art/$v \( -name "*.aar" -o -name "*.jar" \) | grep -v "sources\|javadoc\|-api\.jar" | head -1)
    [ -n "$f" ] || continue
    case "$f" in
      *.aar) unzip -q -o -d "$T/$art" "$f" classes.jar 2>/dev/null && LIBS="$LIBS:$T/$art/classes.jar" ;;
      *.jar) LIBS="$LIBS:$f" ;;
    esac
  done
done
mkdir -p "$T/stub"
{
  echo 'package app.sabbaq'
  echo 'object R { object string {'
  grep -o '<string name="[a-z0-9_]*"' app/src/main/res/values/strings.xml | sed 's/<string name="\(.*\)"/const val \1 = 0/'
  echo '}'
  echo 'object drawable {'
  ls app/src/main/res/drawable* | grep -v ":" | sed 's/\..*//' | sort -u | grep . | sed 's/^/const val /; s/$/ = 0/'
  echo '}'
  echo 'object font {'
  ls app/src/main/res/font | sed 's/\..*//' | sort -u | sed 's/^/const val /; s/$/ = 0/'
  echo '} }'
  echo 'object LocaleManager { fun isArabic(): Boolean = true }'
  echo 'object Settings { var lastConfirmedTapX: Int = -1; var lastConfirmedTapY: Int = -1 }'
} > "$T/stub/R.kt"
cat > "$T/stub/Helpers.kt" <<'EOF'
package app.sabbaq.ui
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
@Composable internal fun ProConditionCard(title: String, subtitle: String?, enabled: Boolean, onEnabledChange: (Boolean) -> Unit, testKey: String,
  modifier: Modifier = Modifier, switchEnabled: Boolean = true, framed: Boolean = true, headerAction: (@Composable () -> Unit)? = null,
  footer: (@Composable () -> Unit)? = null, inputs: @Composable RowScope.(dimmed: Boolean) -> Unit) {}
@Composable internal fun CompactStepperValue(label: String, value: String, unit: String, step: String, keyboardType: KeyboardType,
  onValueChange: (String) -> Unit, testKey: String, modifier: Modifier = Modifier, error: String? = null, dimmed: Boolean = false) {}
EOF
OUT=$(mktemp -d)
SRC="core/src/main/kotlin/app/sabbaq/core/Model.kt $(ls core/src/main/kotlin/app/sabbaq/core/engine/*.kt core/src/main/kotlin/app/sabbaq/core/engine/zones/*.kt) \
  $(ls app/src/main/java/app/sabbaq/service/zones/*.kt) app/src/main/java/app/sabbaq/ui/FilterDraft.kt app/src/main/java/app/sabbaq/ui/ZoneConditions.kt \
  app/src/main/java/app/sabbaq/ui/AdvancedFiltersEditor.kt app/src/main/java/app/sabbaq/ui/ProStepper.kt \
  app/src/main/java/app/sabbaq/ui/DesignComponents.kt $(ls app/src/main/java/app/sabbaq/ui/theme/*.kt) $T/stub/R.kt   app/src/main/java/app/sabbaq/ui/ArabicCount.kt app/src/main/java/app/sabbaq/ui/SabbaqScenes.kt core/src/main/kotlin/app/sabbaq/core/Text.kt"
printf 'package app.sabbaq\nobject BuildConfig { const val VERSION_NAME = "check" }\n' > "$T/stub/BuildConfig.kt"
printf 'package app.sabbaq.service\nclass SabbaqA11yService { fun onConfigSaved() {}\n companion object { var instance: SabbaqA11yService? = null } }\n' >> "$T/stub/BuildConfig.kt.svc.kt"
mv "$T/stub/BuildConfig.kt.svc.kt" "$T/stub/Service.kt"
SRC="$SRC $T/stub/Service.kt"
"$J" -Xmx900m -cp "$(cygpath -wp "$CP_C")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -Xplugin="$(cygpath -w "$SER")" -Xplugin="$(cygpath -w "$COMPOSE")" \
  -cp "$(cygpath -wp "$LIBS")" -d "$(cygpath -w $OUT)" $SRC $T/stub/BuildConfig.kt 2>&1 | grep -v "^warning" || true
ls $OUT/app/sabbaq/ui/ 2>/dev/null | grep -c class
