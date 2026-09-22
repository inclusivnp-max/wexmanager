#!/bin/sh
# Запуск WEXdesktop. Java не нужна — она лежит внутри папки.
#
# Под Wayland (niri, sway, Hyprland) Java без этой переменной рисует окно неправильно
# и теряет фокус, поэтому задаём её здесь.
export _JAVA_AWT_WM_NONREPARENTING=1
DIR=$(cd "$(dirname "$0")" && pwd)
exec "$DIR/bin/WEXdesktop" "$@"
