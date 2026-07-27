#!/usr/bin/env python3
import json, pathlib, sys

def convert(src, dst, texture_ids):
    data = json.loads(pathlib.Path(src).read_text(encoding='utf-8'))
    resolution = data.get('resolution', {'width': 16, 'height': 16})
    width, height = resolution.get('width', 16), resolution.get('height', 16)
    elements = []
    textures = {str(i): tex for i, tex in enumerate(texture_ids)}

    for e in data.get('elements', []):
        if e.get('type', 'cube') != 'cube' or e.get('export', True) is False:
            continue
        frm = [float(v) + 8.0 for v in e.get('from', [0, 0, 0])]
        to = [float(v) + 8.0 for v in e.get('to', [16, 16, 16])]
        faces = {}
        for side, face in e.get('faces', {}).items():
            uv = face.get('uv')
            if not uv:
                continue
            index = int(face.get('texture', 0))
            if index < 0 or index >= len(texture_ids):
                index = 0
            # Critical: Minecraft face references are keys such as #0/#1,
            # not full resource locations such as #carwinch:block/towbar.
            out_face = {'uv': [float(x) for x in uv], 'texture': f'#{index}'}
            if face.get('rotation', 0):
                out_face['rotation'] = face['rotation']
            faces[side] = out_face
        element = {'from': frm, 'to': to, 'faces': faces}
        rotation = e.get('rotation')
        if rotation and any(float(x) != 0 for x in rotation):
            axis_index = next(i for i, x in enumerate(rotation) if float(x) != 0)
            axis = ('x', 'y', 'z')[axis_index]
            element['rotation'] = {
                'origin': [float(x) + 8.0 for x in e.get('origin', [0, 0, 0])],
                'axis': axis,
                'angle': float(rotation[axis_index])
            }
        elements.append(element)

    result = {
        'credit': 'Converted from Blockbench .bbmodel',
        'parent': 'minecraft:block/block',
        'textures': textures,
        'elements': elements
    }
    pathlib.Path(dst).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(dst).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    if len(sys.argv) < 4:
        raise SystemExit('usage: convert_bbmodel.py input.bbmodel output.json texture0 [texture1 ...]')
    convert(sys.argv[1], sys.argv[2], sys.argv[3:])
