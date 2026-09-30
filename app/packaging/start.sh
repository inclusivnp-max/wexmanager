#!/bin/sh
# Запуск WEXdesktop. Java не нужна — она лежит внутри папки.
#
# Под Wayland (niri, sway, Hyprland) Java без этой переменной рисует окно неправильно
# и теряет фокус, поэтому задаём её здесь.
export _JAVA_AWT_WM_NONREPARENTING=1
# Две арены malloc вместо десятков: иначе сотни мегабайт свободной, но не возвращённой памяти.
# NVIDIA: ожидание кадра — сном, а не холостым циклом. Подробно — nativeEnv в app/build.gradle.kts.
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
export __GL_YIELD="${__GL_YIELD:-USLEEP}"
DIR=$(cd "$(dirname "$0")" && pwd)
exec "$DIR/bin/WEXdesktop" "$@"
