#!/usr/bin/env bash
# Пересборка тестовых фикстур (app/src/test/resources/fixtures/gen/*.dex и fixtures/classes/*.class)
# из исходников в этом каталоге:
#   java/<name>/**  -> javac --release 8 -> d8 -> gen/<name>.dex
#   smali/<name>/** -> эталонный smali 3.0.9 (smali a --api N) -> gen/<name>.dex
# Требуется: JDK 11+ (javac --release), Android SDK (build-tools/*/d8, platforms/*/android.jar).
# Эталонный smali берётся из SMALI_CP (classpath) или из Gradle (:app:smaliClasspath).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
RES="$ROOT/app/src/test/resources/fixtures"
GEN="$RES/gen"
CLS="$RES/classes"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] && [ -x "$BT/d8" ] || { echo "Не найден d8 в $SDK/build-tools" >&2; exit 1; }
D8="$BT/d8"
PLATFORM="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "Не найден android.jar в $SDK/platforms" >&2; exit 1; }

if [ -z "${SMALI_CP:-}" ]; then
  SMALI_CP="$("$ROOT/gradlew" -q -p "$ROOT" :app:smaliClasspath)"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$GEN" "$CLS"

# javac_dir <каталог исходников> <каталог классов> <опции javac...>
javac_dir() {
  local src="$1" out="$2"; shift 2
  mkdir -p "$out"
  find "$src" -name '*.java' -print0 | xargs -0 javac -nowarn -encoding UTF-8 -d "$out" "$@"
}

# dex_java <имя> <опции d8...>: java/<имя> -> gen/<имя>.dex
dex_java() {
  local name="$1"; shift
  javac_dir "$HERE/java/$name" "$WORK/$name/classes" --release 8 -g
  mkdir -p "$WORK/$name/dex"
  find "$WORK/$name/classes" -name '*.class' -print0 | xargs -0 "$D8" --lib "$ANDROID_JAR" --output "$WORK/$name/dex" "$@"
  cp "$WORK/$name/dex/classes.dex" "$GEN/$name.dex"
}

# dex_smali <имя> <api>: smali/<имя> -> gen/<имя>.dex
dex_smali() {
  java -cp "$SMALI_CP" com.android.tools.smali.smali.Main a --api "$2" -o "$GEN/$1.dex" "$HERE/smali/$1"
}

dex_java switches
dex_java consts
dex_java enums
dex_java annotations
dex_java trycatch --min-api 24
dex_java misc --release --min-api 26

# debug: один DEX из классов, собранных с разными -g
javac_dir "$HERE/java/debug/vars" "$WORK/debug/classes" --release 8 -g
javac_dir "$HERE/java/debug/novars" "$WORK/debug/classes" --release 8 -g:source,lines
javac_dir "$HERE/java/debug/none" "$WORK/debug/classes" --release 8 -g:none
mkdir -p "$WORK/debug/dex"
find "$WORK/debug/classes" -name '*.class' -print0 | xargs -0 "$D8" --lib "$ANDROID_JAR" --output "$WORK/debug/dex"
cp "$WORK/debug/dex/classes.dex" "$GEN/debug.dex"

dex_smali allops 15
dex_smali casecollide 15
dex_smali debugdirs 15
dex_smali hello 15
dex_smali longname 15
dex_smali invoke 28
dex_smali hiddenapi 29

# .class для round-trip ClassReader/ClassWriter
cp "$WORK/switches/classes/fx/sw/Switches.class" "$CLS/"
cp "$WORK/annotations/classes/fx/ann/Annotated.class" "$WORK/annotations/classes/fx/ann/All.class" "$CLS/"
cp "$WORK/trycatch/classes/fx/tc/Tc.class" "$CLS/"
javac_dir "$HERE/java/modern" "$WORK/modern/classes" --release 21 -g -parameters
cp "$WORK/modern/classes/fx/modern/"*.class "$CLS/"

ls -l "$GEN" "$CLS"
