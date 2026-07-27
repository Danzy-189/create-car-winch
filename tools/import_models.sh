#!/usr/bin/env bash
set -euo pipefail
BASE="${MODEL_BASE_URL:-https://raw.githubusercontent.com/Danzy-189/create-car-winch/main/1}"
mkdir -p models textures
fetch() { curl --fail --location --retry 3 --show-error "$BASE/$1" -o "$2"; }
fetch winch.bbmodel models/winch.bbmodel
fetch winch_1.bbmodel models/winch_1.bbmodel
fetch towbar.bbmodel models/towbar.bbmodel
fetch towbar_1.bbmodel models/towbar_1.bbmodel
fetch winch.png textures/winch.png
fetch winch_drum.png textures/winch_drum.png
fetch towbar.png textures/towbar.png
fetch winch_hook.png textures/winch_hook.png
fetch iron_rope.png textures/iron_rope.png

# Correct texture mapping: empty winch only winch.png; filled winch only winch_drum.png.
python3 tools/convert_bbmodel.py models/winch.bbmodel src/main/resources/assets/carwinch/models/block/winch.json carwinch:block/winch
python3 tools/convert_bbmodel.py models/winch_1.bbmodel src/main/resources/assets/carwinch/models/block/winch_1.json carwinch:block/winch_drum
python3 tools/convert_bbmodel.py models/towbar.bbmodel src/main/resources/assets/carwinch/models/block/towbar.json carwinch:block/towbar
python3 tools/convert_bbmodel.py models/towbar_1.bbmodel src/main/resources/assets/carwinch/models/block/towbar_1.json carwinch:block/towbar carwinch:block/winch_hook

mkdir -p src/main/resources/assets/carwinch/textures/block src/main/resources/assets/carwinch/textures/item src/main/resources/assets/carwinch/models/item
cp textures/winch.png src/main/resources/assets/carwinch/textures/block/winch.png
cp textures/winch_drum.png src/main/resources/assets/carwinch/textures/block/winch_drum.png
cp textures/towbar.png src/main/resources/assets/carwinch/textures/block/towbar.png
cp textures/winch_hook.png src/main/resources/assets/carwinch/textures/block/winch_hook.png
cp textures/iron_rope.png src/main/resources/assets/carwinch/textures/item/iron_rope.png
printf '%s\n' '{"parent":"carwinch:block/winch"}' > src/main/resources/assets/carwinch/models/item/winch.json
printf '%s\n' '{"parent":"carwinch:block/towbar"}' > src/main/resources/assets/carwinch/models/item/towbar.json
cat > src/main/resources/assets/carwinch/models/item/iron_rope.json <<'JSON'
{"parent":"item/generated","textures":{"layer0":"carwinch:item/iron_rope"}}
JSON

