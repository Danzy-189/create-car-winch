#!/usr/bin/env bash
set -euo pipefail
BASE="${MODEL_BASE_URL:-https://raw.githubusercontent.com/Danzy-189/create-car-winch/main/1}"
mkdir -p models textures
for f in winch.bbmodel winch_1.bbmodel towbar.bbmodel towbar_1.bbmodel; do
  curl -fL "$BASE/$f" -o "models/$f"
done
for f in winch.png winch_drum.png towbar.png winch_hook.png iron_rope.png; do
  curl -fL "$BASE/$f" -o "textures/$f"
done
python3 tools/convert_bbmodel.py models/winch.bbmodel src/main/resources/assets/carwinch/models/block/winch.json carwinch:block/winch
python3 tools/convert_bbmodel.py models/winch_1.bbmodel src/main/resources/assets/carwinch/models/block/winch_1.json carwinch:block/winch_drum
python3 tools/convert_bbmodel.py models/towbar.bbmodel src/main/resources/assets/carwinch/models/block/towbar.json carwinch:block/towbar
python3 tools/convert_bbmodel.py models/towbar_1.bbmodel src/main/resources/assets/carwinch/models/block/towbar_1.json carwinch:block/towbar carwinch:block/winch_hook
mkdir -p src/main/resources/assets/carwinch/textures/block src/main/resources/assets/carwinch/textures/item
cp textures/winch.png src/main/resources/assets/carwinch/textures/block/winch.png
cp textures/winch_drum.png src/main/resources/assets/carwinch/textures/block/winch_drum.png
cp textures/towbar.png src/main/resources/assets/carwinch/textures/block/towbar.png
cp textures/winch_hook.png src/main/resources/assets/carwinch/textures/block/winch_hook.png
cp textures/iron_rope.png src/main/resources/assets/carwinch/textures/item/iron_rope.png
