#!/usr/bin/env python3
"""Ayudante de los tests del PC (el android.jar no trae java.awt/ImageIO).
   a_ppm  ENTRADA SALIDA.ppm          cualquier imagen -> PPM (RGB)
   jpeg   ENTRADA.ppm CALIDAD SALIDA  JPEG con libjpeg (como Bitmap.compress)
   png    ENTRADA.ppm SALIDA.png      PNG RGB (para guardar muestras)"""
import sys
from PIL import Image
op = sys.argv[1]
if op == 'a_ppm':
    Image.open(sys.argv[2]).convert('RGB').save(sys.argv[3], format='PPM')
elif op == 'jpeg':
    Image.open(sys.argv[2]).convert('RGB').save(sys.argv[4], format='JPEG', quality=int(sys.argv[3]))
elif op == 'png':
    Image.open(sys.argv[2]).convert('RGB').save(sys.argv[3], format='PNG', optimize=True)
else:
    sys.exit('operación desconocida')
