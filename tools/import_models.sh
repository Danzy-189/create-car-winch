#!/usr/bin/env bash
#
# Копирует арт из папки 1/ в ресурсы мода и прогоняет модели через fix_model.py.
#
# Скрипт сознательно не роняет сборку: арт в репозитории необязателен,
# а готовые модели и текстуры уже лежат в assets. Если чего-то нет,
# выводится предупреждение GitHub Actions и шаг завершается успешно.

set -euo pipefail

ART="1"
ASSETS="src/main/resources/assets/carwinch"

warn() {
  echo "::warning::$*"
}

copy_if_present() {
  local source="$1"
  local destination="$2"

  if [ -f "$source" ]; then
    cp "$source" "$destination"
  else
    warn "Нет файла $source, пропускаю"
  fi
}

# Предупреждает, если два варианта модели совпадают байт в байт:
# тогда состояние блока визуально ничем не отличается.
check_variants_differ() {
  local base="$1"
  local variant="$2"

  local first="$ASSETS/models/block/$base.json"
  local second="$ASSETS/models/block/$variant.json"

  if [ -f "$first" ] && [ -f "$second" ] && cmp -s "$first" "$second"; then
    warn "Модели $base и $variant идентичны: состояние блока не будет видно в игре"
  fi
}

mkdir -p "$ASSETS/models/block" "$ASSETS/textures/block" "$ASSETS/textures/item"

if [ ! -d "$ART" ]; then
  warn "Папки $ART нет, использую уже закоммиченные ресурсы"
  exit 0
fi

copy_if_present "$ART/winch.png"      "$ASSETS/textures/block/winch.png"
copy_if_present "$ART/winch_drum.png" "$ASSETS/textures/block/winch_drum.png"
copy_if_present "$ART/towbar.png"     "$ASSETS/textures/block/towbar.png"
copy_if_present "$ART/winch_hook.png" "$ASSETS/textures/block/winch_hook.png"
copy_if_present "$ART/iron_rope.png"  "$ASSETS/textures/item/iron_rope.png"

if ! command -v python3 >/dev/null 2>&1; then
  warn "python3 недоступен, модели не пересобираются"
  exit 0
fi

for m in winch winch_1 towbar towbar_1; do
  if [ -f "$ART/$m.json" ]; then
    python3 tools/fix_model.py "$ART/$m.json" "$ASSETS/models/block/$m.json"
  else
    warn "Нет модели $ART/$m.json, пропускаю"
  fi
done

check_variants_differ towbar towbar_1
check_variants_differ winch winch_1
