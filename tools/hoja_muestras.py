#!/usr/bin/env python3
"""Hoja de contactos de las muestras generadas por ImagenTransporteTest:
original | lo que se envía/llega (ampliado sin suavizar, al mismo ancho)."""
import os, sys
from PIL import Image, ImageDraw
raiz = sys.argv[1] if len(sys.argv) > 1 else '/workspace/lesvc-img-samples'
casos = sorted(d for d in os.listdir(raiz) if os.path.isdir(os.path.join(raiz, d)) and d != 'originales')
W = 320; filas = []
for c in casos:
    base = c.split('_')[0] + '_' + c.split('_')[1]
    orig = next((os.path.join(raiz, 'originales', f) for f in os.listdir(os.path.join(raiz, 'originales')) if f.startswith(base)), None)
    dec = Image.open(os.path.join(raiz, c, 'decodificado.png')).convert('RGB')
    o = Image.open(orig).convert('RGB'); o.thumbnail((W, W))
    f = W / max(dec.size); d2 = dec.resize((round(dec.width * f), round(dec.height * f)), Image.NEAREST)
    env = [x for x in os.listdir(os.path.join(raiz, c)) if x.startswith('enviado')][0]
    tam = os.path.getsize(os.path.join(raiz, c, env))
    filas.append((c, o, d2, f'{c}  ·  {env}  {tam} B  ·  {dec.width}x{dec.height} px'))
alto = sum(max(o.height, d.height) + 24 for _, o, d, _ in filas)
hoja = Image.new('RGB', (2 * W + 30, alto), 'white'); y = 0; dr = ImageDraw.Draw(hoja)
for c, o, d, txt in filas:
    dr.text((5, y + 4), txt, fill='black'); y += 20
    hoja.paste(o, (5, y)); hoja.paste(d, (W + 20, y)); y += max(o.height, d.height) + 4
hoja.save(os.path.join(raiz, 'hoja_de_contactos.png')); print(os.path.join(raiz, 'hoja_de_contactos.png'))
