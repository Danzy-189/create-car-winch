#!/usr/bin/env python3
"""Приводит экспорт Blockbench к тому, что реально ест Minecraft."""
import json, pathlib, sys

src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
art = src.parent
known = {p.stem for p in art.glob("*.png")}

model = json.loads(src.read_text(encoding="utf-8"))
if "elements" not in model:
    sys.exit(f"{src}: это не блок-модель. Blockbench -> File -> Export -> Block/Item Model")

model.pop("credit", None)
model.pop("format_version", None)

# UUID-ключи -> человеческие имена, голые имена -> carwinch:block/...
rename, textures = {}, {}
for key, value in model.get("textures", {}).items():
    name = value.split(":")[-1].split("/")[-1]
    if name not in known:
        sys.exit(f"{src}: неизвестная текстура '{value}', в {art}/ такой png нет")
    folder = "item" if name == "iron_rope" else "block"
    rename[key] = name
    textures[name] = f"carwinch:{folder}/{name}"
if not textures:
    sys.exit(f"{src}: в модели вообще нет текстур")
textures["particle"] = next(iter(textures.values()))
model["textures"] = textures

for el in model["elements"]:
    for face in el.get("faces", {}).values():
        ref = face.get("texture", "")
        if ref.startswith("#"):
            face["texture"] = "#" + rename.get(ref[1:], ref[1:])
    for corner in ("from", "to"):
        for v in el[corner]:
            if not -16 <= v <= 32:
                sys.exit(f"{src}: элемент '{el.get('name')}' вылезает за -16..32 ({corner}={el[corner]})")

model.setdefault("parent", "minecraft:block/block")  # нормальные display-трансформы в руке и GUI

dst.write_text(json.dumps(model, indent=2), encoding="utf-8")
print(f"{src} -> {dst} ({len(model['elements'])} элементов, текстуры: {', '.join(sorted(textures))})")
