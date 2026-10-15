#!/usr/bin/env python3
"""Rasterize the shared Android 24x24 feature paths as a 3x contact sheet."""
from pathlib import Path
import json, math, re
from PIL import Image, ImageDraw, ImageFont
root = Path(__file__).resolve().parents[2]
data = json.loads((root/'android/app/src/main/assets/padnote-tool-icons.json').read_text())
names = list(data)
scale = 3
cell_w, cell_h, cols, margin = 132*scale, 106*scale, 5, 18*scale
rows = math.ceil(len(names)/cols)
im = Image.new('RGB',(margin*2+cell_w*cols,margin*2+cell_h*rows),'#f4f1e8')
draw = ImageDraw.Draw(im)
try: font = ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',10*scale)
except OSError: font = ImageFont.load_default()
colors=['#e84855','#c04bb9','#4d66d8','#37aed2','#40b070','#ebbe41']

def parse(source):
    tokens=re.findall(r'[A-Za-z]|-?(?:\d*\.\d+|\d+)',source)
    out=[]; i=0; cmd=''; x=y=0; sx=sy=0
    while i<len(tokens):
        if tokens[i].isalpha(): cmd=tokens[i]; i+=1
        if cmd=='M': x,y=float(tokens[i]),float(tokens[i+1]);i+=2;sx,sy=x,y;out.append(('M',x,y));cmd='L'
        elif cmd=='L': x,y=float(tokens[i]),float(tokens[i+1]);i+=2;out.append(('L',x,y))
        elif cmd=='H': x=float(tokens[i]);i+=1;out.append(('L',x,y))
        elif cmd=='V': y=float(tokens[i]);i+=1;out.append(('L',x,y))
        elif cmd=='C':
            x1,y1,x2,y2,x3,y3=map(float,tokens[i:i+6]);i+=6;out.append(('C',x,y,x1,y1,x2,y2,x3,y3));x,y=x3,y3
        elif cmd=='Z': out.append(('Z',));x,y=sx,sy;cmd=''
        else: raise ValueError(f'Unsupported path command {cmd}')
    return out

def xypt(x,y,cx,cy):
    return (cx+(x-12)*scale, cy+(y-12)*scale)

def draw_path(source,cx,cy,color,dashed=False):
    pts=[]
    for op in parse(source):
        if op[0]=='M':
            if len(pts)>1: draw.line(pts,fill=color,width=2*scale,joint='curve')
            pts=[xypt(op[1],op[2],cx,cy)]
        elif op[0]=='L': pts.append(xypt(op[1],op[2],cx,cy))
        elif op[0]=='C':
            _,x0,y0,x1,y1,x2,y2,x3,y3=op
            for n in range(1,17):
                t=n/16;u=1-t
                x=u**3*x0+3*u*u*t*x1+3*u*t*t*x2+t**3*x3
                y=u**3*y0+3*u*u*t*y1+3*u*t*t*y2+t**3*y3
                pts.append(xypt(x,y,cx,cy))
        elif op[0]=='Z' and len(pts)>1: pts.append(pts[0])
    if len(pts)>1:
        if dashed:
            for a,b in zip(pts,pts[1:]):
                length=math.dist(a,b); steps=max(1,int(length/(3*scale)))
                for k in range(0,steps,2):
                    f0=k/steps;f1=min(1,(k+1)/steps)
                    draw.line((a[0]+(b[0]-a[0])*f0,a[1]+(b[1]-a[1])*f0,a[0]+(b[0]-a[0])*f1,a[1]+(b[1]-a[1])*f1),fill=color,width=2*scale)
        else: draw.line(pts,fill=color,width=2*scale,joint='curve')
    for px,py in pts:
        draw.ellipse((px-scale,py-scale,px+scale,py+scale),fill=color)

for index,name in enumerate(names):
    col,row=index%cols,index//cols
    x=margin+col*cell_w; y=margin+row*cell_h
    draw.rounded_rectangle((x,y,x+cell_w-24*scale,y+cell_h-24*scale),radius=12*scale,fill='white',outline='#ece9e2',width=scale)
    cx=x+(cell_w-24*scale)//2;cy=y+cell_h-48*scale
    if name=='PALETTE':
        box=(cx-9*scale,cy-9*scale,cx+9*scale,cy+9*scale)
        for j,c in enumerate(colors): draw.arc(box,start=j*60,end=(j+1)*60,fill=c,width=2*scale)
        draw.ellipse((cx-4*scale,cy-4*scale,cx+4*scale,cy+4*scale),fill='#285ea8')
    else:
        for i,path in enumerate(data[name]):
            color='#285ea8' if name in ('SHAPE','PAGE_ADD','PAGE_MENU','IMAGE') or (name in ('PEN','HIGHLIGHTER') and i==2) else '#1f2933'
            draw_path(path,cx,cy,color,dashed=(name=='LASSO' and i==0))
    label=name.replace('_',' ')
    draw.text((x+9*scale,y+8*scale),label,fill='#6b7684',font=font)
out=root/'assets/brand/tool-icons-contact.png'
im.save(out)
print(out)
