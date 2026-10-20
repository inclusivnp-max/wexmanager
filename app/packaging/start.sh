#!/bin/sh
set -eu

# ChildEnv возвращает эти значения программам, запущенным из WEX.
# Пустая метка означает «не задано», =значение — исходное значение переменной.
if [ "${WEX_ORIG__JAVA_AWT_WM_NONREPARENTING+x}" != x ]; then
    export WEX_ORIG__JAVA_AWT_WM_NONREPARENTING="${_JAVA_AWT_WM_NONREPARENTING+=${_JAVA_AWT_WM_NONREPARENTING}}"
fi
if [ "${WEX_ORIG_MALLOC_ARENA_MAX+x}" != x ]; then
    export WEX_ORIG_MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX+=${MALLOC_ARENA_MAX}}"
fi
if [ "${WEX_ORIG___GL_YIELD+x}" != x ]; then
    export WEX_ORIG___GL_YIELD="${__GL_YIELD+=${__GL_YIELD}}"
fi

export _JAVA_AWT_WM_NONREPARENTING=1
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
export __GL_YIELD="${__GL_YIELD:-USLEEP}"

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Параметры JVM берём из конфигурации jpackage, не дублируем их здесь.
user_args=$#
while IFS= read -r line; do
    case "$line" in
        java-options=*) set -- "$@" "${line#java-options=}" ;;
    esac
done < "$DIR/lib/app/WEXdesktop.cfg"
set -- "$@" -cp "$DIR/lib/app/app.jar" wexdesktop
# Переносим аргументы приложения после параметров JVM, сохраняя порядок и пробелы.
while [ "$user_args" -gt 0 ]; do
    arg=$1
    shift
    set -- "$@" "$arg"
    user_args=$((user_args - 1))
done
exec "$DIR/lib/runtime/bin/java" "$@"
