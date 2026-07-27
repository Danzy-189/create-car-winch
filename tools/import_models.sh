#!/usr/bin/env bash
set -euo pipefail

ART="1"
ASSETS="src/main/resources/assets/carwinch"

mkdir -p "$ASSETS/models/block" "$ASSETS/textures/block" "$ASSETS/textures/item"

cp "$ART/winch.png"      "$ASSETS/textures/block/winch.png"
cp "$ART/winch_drum.png" "$ASSETS/textures/block/winch_drum.png"
cp "$ART/towbar.png"     "$ASSETS/textures/block/towbar.png"
cp "$ART/winch_hook.png" "$ASSETS/textures/block/winch_hook.png"
cp "$ART/iron_rope.png"  "$ASSETS/textures/item/iron_rope.png"

for m in winch winch_1 towbar towbar_1; do
  python3 tools/fix_model.py "$ART/$m.json" "$ASSETS/models/block/$m.json"
done
