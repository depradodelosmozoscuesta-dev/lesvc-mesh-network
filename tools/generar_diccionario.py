#!/usr/bin/env python3
"""Genera app/src/main/assets/compresion/v1/diccionario_conversacional.txt (v1).

Fuente de frecuencias: los mismos textos de entrenamiento del compresor
(es_libros.txt, dominio público; es_chat.txt ×20, escrito para el proyecto)
+ frases de mensajería añadidas a mano. NO regenerar para v1 (rompería la
compatibilidad): para cambios crear v2.
Formato: una entrada por línea. Líneas 1–200: códigos de 1 byte. Resto (hasta
10240): códigos de 2 bytes. Entradas en minúsculas; una frase = palabras
separadas por un espacio.
"""
import re, collections, pathlib
base = pathlib.Path(__file__).resolve().parent.parent / 'app/src/main/assets/compresion/v1'
libros = (base / 'es_libros.txt').read_text(encoding='utf-8').split('\n')
chat = (base / 'es_chat.txt').read_text(encoding='utf-8').split('\n')
PUNT = [',', '.', '?', '!', '¿', '¡', ':', ';', '(', ')', '"', '-', '...']
FRASES = ['nos vemos', 'a las', 'de la', 'en la', 'por favor', 'muchas gracias', 'hasta luego',
  'hasta mañana', 'buenos días', 'buenas tardes', 'buenas noches', 'qué tal', 'de nada',
  'hemos quedado', 'he quedado', 'te quiero', 'un abrazo', 'por la mañana', 'por la tarde',
  'por la noche', 'esta noche', 'esta tarde', 'mañana por la mañana', 'ahora mismo', 'te llamo',
  'me llamas', 'estoy en', 'estoy llegando', 'ya estoy', 'no puedo', 'no sé', 'lo siento',
  'claro que sí', 'a lo mejor', 'en casa', 'de verdad', 'todo bien', 'sin problema', 'que no',
  'que sí', 'a la', 'de los', 'en el', 'del', 'para que', 'lo que', 'y media', 'en punto']
tok = re.compile(r"[^\W\d_]+", re.UNICODE)
cnt = collections.Counter()
for t in libros + chat * 20:
    for w in tok.findall(t.lower()):
        cnt[w] += 1
uno = PUNT + FRASES
for w, _ in cnt.most_common():
    if len(uno) >= 200: break
    if w not in uno and len(w) >= 1: uno.append(w)
dos = []
vistos = set(uno)
for w, c in cnt.most_common():
    if len(dos) >= 40 * 256: break
    if c < 2: break
    if w not in vistos and len(w) >= 2: dos.append(w); vistos.add(w)
(base / 'diccionario_conversacional.txt').write_text('\n'.join(uno + dos) + '\n', encoding='utf-8')
print(len(uno), len(dos))
