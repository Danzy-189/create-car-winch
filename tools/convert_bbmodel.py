#!/usr/bin/env python3
import json, pathlib, sys

def convert(src, dst, texture_map):
    data=json.loads(pathlib.Path(src).read_text(encoding='utf-8'))
    resolution=data.get('resolution', {'width':16,'height':16})
    tw,th=resolution.get('width',16), resolution.get('height',16)
    elements=[]
    for e in data.get('elements',[]):
        if e.get('type','cube')!='cube' or e.get('export',True) is False:
            continue
        f=e.get('from',[0,0,0]); t=e.get('to',[16,16,16])
        # Blockbench free-format coordinates are centered around 0; Minecraft JSON is 0..16.
        frm=[float(v)+8 for v in f]; to=[float(v)+8 for v in t]
        faces={}
        for side,face in e.get('faces',{}).items():
            uv=face.get('uv')
            if not uv: continue
            tex=texture_map.get(str(face.get('texture',0)), texture_map.get('0','namespace:block/texture'))
            faces[side]={'uv':[float(x) for x in uv], 'texture':'#'+tex}
            if face.get('rotation',0): faces[side]['rotation']=face['rotation']
        elem={'from':frm,'to':to,'faces':faces}
        rot=e.get('rotation')
        if rot and any(float(x)!=0 for x in rot):
            # Rotation origin in Blockbench coordinates, converted to Minecraft coordinates.
            elem['rotation']={'origin':[float(x)+8 for x in e.get('origin',[0,0,0])], 'axis':'y' if rot[1] else ('x' if rot[0] else 'z'), 'angle':float(next(x for x in rot if x))}
        elements.append(elem)
    out={'credit':'Converted from Blockbench .bbmodel','parent':'minecraft:block/block','textures':{}}
    for key,val in texture_map.items(): out['textures'][key]=val
    out['elements']=elements
    pathlib.Path(dst).parent.mkdir(parents=True,exist_ok=True)
    pathlib.Path(dst).write_text(json.dumps(out,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')

if __name__=='__main__':
    if len(sys.argv)<4:
        print('usage: convert_bbmodel.py input.bbmodel output.json texture0 texture1 ...'); raise SystemExit(2)
    convert(sys.argv[1],sys.argv[2],{str(i):v for i,v in enumerate(sys.argv[3:])})
