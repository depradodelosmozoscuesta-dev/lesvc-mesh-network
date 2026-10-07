#!/usr/bin/env python3
"""Compara nuestros codecs (medidos en Kotlin por CompresionTest → compresion_por_mensaje.tsv)
con códecs establecidos sobre EXACTAMENTE los mismos mensajes y los mismos textos de entrenamiento:
  - Unishox2 (siara-cc, Apache-2.0), sin diccionario (no admite uno entrenado)
  - zstd 1.5 con diccionario entrenado (64 KB, formato sin número mágico, nivel 19)
  - Brotli 1.1 con diccionario propio (el mismo contenido de 64 KB, calidad 9: con 10–11 la versión 1.1.0 no aprovecha el diccionario en mensajes cortos, medido)
  - deflate (zlib) con diccionario preestablecido de 32 KB
Uso: python benchmark_compresores.py TSV ASSETS_DIR BENCH_BIN_DIR > informe.md"""
import sys, os, statistics, subprocess, zlib
import zstandard, brotli  # noqa

tsv, assets, binDir = sys.argv[1], sys.argv[2], sys.argv[3]
filas = [l.rstrip('\n').split('\t') for l in open(tsv, encoding='utf-8')][1:]
lee = lambda n: [x.strip() for x in open(os.path.join(assets, n), encoding='utf-8') if x.strip()]
libros, chat, urls = lee('es_libros.txt'), lee('es_chat.txt'), lee('urls.txt')

def entrenar(muestras, tam=65536):
    return zstandard.train_dictionary(tam, [m.encode() for m in muestras], level=19)

dic_texto = entrenar(chat * 20 + libros)
dic_url = entrenar(urls * 20 + chat)

def zstd_len(s, d):
    params = zstandard.ZstdCompressionParameters.from_level(19, format=zstandard.FORMAT_ZSTD1_MAGICLESS, write_checksum=False, write_content_size=False, write_dict_id=False)
    c = zstandard.ZstdCompressor(dict_data=d, compression_params=params)
    out = c.compress(s.encode())
    dd = zstandard.ZstdDecompressor(dict_data=d, format=zstandard.FORMAT_ZSTD1_MAGICLESS)
    assert dd.decompress(out, max_output_size=1 << 20) == s.encode()
    return len(out)

def deflate_len(s, zd):
    c = zlib.compressobj(9, zlib.DEFLATED, -15, 9, zlib.Z_DEFAULT_STRATEGY, zd)
    out = c.compress(s.encode()) + c.flush()
    d = zlib.decompressobj(-15, zd); assert d.decompress(out) == s.encode()
    return len(out)

def externo(binario, args, textos):
    p = subprocess.run([os.path.join(binDir, binario)] + args, input='\n'.join(textos) + '\n', capture_output=True, text=True, check=True)
    res = [l.split() for l in p.stdout.strip().split('\n')]
    assert all(r[1] == '1' for r in res), binario + ': ida y vuelta fallida'
    return [int(r[0]) for r in res]

os.makedirs('/tmp/lesvc-bench', exist_ok=True)
for nombre, d in (('texto', dic_texto), ('url', dic_url)):
    open(f'/tmp/lesvc-bench/dic_{nombre}.bin', 'wb').write(d.as_bytes())

corpus = {}
for f in filas:
    corpus.setdefault(f[0], []).append(f)

metodos = ['UTF-8 tal cual', 'Unishox2', 'deflate + dicc. 32 KB', 'zstd + dicc. entrenado 64 KB', 'Brotli + dicc. propio 64 KB',
           'PPM orden 2 (nuestro)', 'PPM orden 3 (nuestro)', 'PPM orden 4 (nuestro, 0x11)', 'Conversacional bytes (0x13)',
           'Conversacional + bits (0x14)', 'URL combo+PPM4 (0x12)', 'HÍBRIDO enviado (mínimo por mensaje)']
print('## Comparativa con códecs establecidos (mismos mensajes, mismo texto de entrenamiento)\n')
print('Tamaños en bytes; los nuestros incluyen el byte de cabecera de tipo. bits/car = 8·bytes/caracteres.\n')
for nombre, fs in corpus.items():
    textos = [f[12] for f in fs]
    chars = sum(int(f[1]) for f in fs)
    d = dic_url if nombre == 'urls' else dic_texto
    dpath = f'/tmp/lesvc-bench/dic_{"url" if nombre == "urls" else "texto"}.bin'
    zd = d.as_bytes()[-32768:]
    tam = {m: [] for m in metodos}
    uni = externo('uni_bench', [], textos); bro = externo('bro_bench', [dpath], textos)
    for i, (f, t) in enumerate(zip(fs, textos)):
        tam['UTF-8 tal cual'].append(int(f[2]))
        tam['Unishox2'].append(uni[i])
        tam['deflate + dicc. 32 KB'].append(deflate_len(t, zd))
        tam['zstd + dicc. entrenado 64 KB'].append(zstd_len(t, d))
        tam['Brotli + dicc. propio 64 KB'].append(bro[i])
        tam['PPM orden 2 (nuestro)'].append(int(f[7])); tam['PPM orden 3 (nuestro)'].append(int(f[8]))
        tam['PPM orden 4 (nuestro, 0x11)'].append(int(f[9])); tam['Conversacional bytes (0x13)'].append(int(f[4]))
        tam['Conversacional + bits (0x14)'].append(int(f[6]))
        if f[10]: tam['URL combo+PPM4 (0x12)'].append(int(f[10]))
        tam['HÍBRIDO enviado (mínimo por mensaje)'].append(min(int(f[11]), int(f[2])))
    raw = tam['UTF-8 tal cual']
    print(f'### {nombre} ({len(fs)} mensajes, {chars} caracteres, {sum(raw)} bytes UTF-8)\n')
    print('| Método | Bytes | Ahorro total | Ahorro medio por mensaje | Mediana | bits/car |\n|---|---|---|---|---|---|')
    for m in metodos:
        v = tam[m]
        if len(v) != len(fs): continue
        ah = [1 - a / b for a, b in zip(v, raw)]
        print(f'| {m} | {sum(v)} | {100 - 100 * sum(v) / sum(raw):.1f} % | {100 * statistics.mean(ah):.1f} % | {100 * statistics.median(ah):.1f} % | {8 * sum(v) / chars:.2f} |')
    print()
