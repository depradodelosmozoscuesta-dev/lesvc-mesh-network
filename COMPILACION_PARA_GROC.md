# 📱 LESVC Mesh Audio – Cómo se compila (versión real, 0.3)

Mensajes de texto **e imágenes** por sonido entre móviles Android, sin internet, usando
**ggwave** (C/C++, MIT) a través de JNI. Desde la 0.3 los textos viajan comprimidos
(diccionario estático en español) cuando así tardan menos.

---

## Qué hay en el proyecto

```
settings.gradle, build.gradle, gradle.properties, gradlew(.bat), gradle/wrapper/   → Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24
app/build.gradle                       → com.lesvc.mesh, versionCode 3 / 0.3, minSdk 21, target 34,
                                          NDK 26.1.10909125, CMake 3.22.1, ABIs arm64-v8a / armeabi-v7a / x86_64
app/src/main/cpp/
    CMakeLists.txt                     → compila ggwave (estático) + liblesvc_ggwave.so (JNI)
    ggwave_jni.cpp                     → puente JNI (init / free / encode / decode, audio mono PCM16)
    ggwave/                            → ggwave-v0.4.3 (a38e38b) SIN modificar + LICENSE + VERSION.txt
app/src/main/kotlin/com/lesvc/mesh/
    MainActivity.kt                    → interfaz en español (texto, historial con imágenes, recepción/reenvío)
    EnviarImagenActivity.kt            → elegir imagen (galería/cámara), tiempo, tipo y vista previa exacta
    archivo/ProtocoloArchivo.kt        → tramas binarias 0x1F: cabecera H, datos D, petición de reenvío R
    archivo/ReceptorArchivos.kt        → reensamblado (hasta 1000 partes), CRC32, lista de faltan
    archivo/Presupuesto.kt             → segundos ↔ bytes exactos
    imagen/                            → reducción, paleta/PNG indexado, búsqueda de calidad, MediaStore
    texto/                             → compresión: PPM + codificador de rango, URL, conversacional
    audio/Duracion.kt                  → duración EXACTA de cada emisión (fórmula comprobada en tests)
    audio/GGWave.kt                    → envoltorio Kotlin (agrupa el audio en tramas de 1024 muestras)
    audio/Protocolo.kt                 → 6 modos: audible normal/rápido/muy rápido, ultrasonido ×3
    audio/Troceador.kt                 → UTF-8, límite 140 bytes, troceo y reensamblado
    audio/Emisor.kt                    → AudioTrack mono 48 kHz (streaming)
    audio/Receptor.kt                  → AudioRecord mono continuo → ggwave
    audio/Remuestreador.kt             → 44,1 → 48 kHz para micros que no graban a 48 kHz
app/src/main/assets/licenses/          → licencias MIT mostradas en «Acerca de»
app/src/main/assets/compresion/v1/     → diccionario v1 CONGELADO (textos de entrenamiento + diccionario conversacional)
app/src/test/…                         → pruebas JVM extremo a extremo (sin móvil)
tools/build-host-jni.sh                → compila el mismo código nativo para el PC (lo usan los tests)
tools/imagen_host.py                   → JPEG/lectura de imágenes para los tests del PC (Pillow)
tools/generar_diccionario.py           → regenera el diccionario conversacional (solo para una v2)
tools/benchmark_compresores.py         → comparación con Unishox2 / zstd / Brotli / deflate
tools/turbo/turbo_eval.cpp             → evaluación de protocolos ggwave personalizados (no va en el APK)
legacy/sonic-stereo/                   → el codificador antiguo, fuera de la compilación
```

## Requisitos

- JDK 17 o superior (probado con OpenJDK 21)
- Android SDK con: `platforms;android-34`, `build-tools;34.0.0`,
  **`ndk;26.1.10909125`** y **`cmake;3.22.1`**
  (`sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`; Android Studio los instala solo)
- Para los tests en el PC: compilador C++ (g++ o clang), `python3` con Pillow y
  `cwebp`/`dwebp` (paquete `webp`) – en Linux/macOS

## Compilar

```bash
git clone https://github.com/depradodelosmozoscuesta-dev/lesvc-mesh-network.git
cd lesvc-mesh-network
echo "sdk.dir=/ruta/a/Android/sdk" > local.properties     # o exporta ANDROID_HOME
./gradlew assembleDebug                                     # APK de depuración
./gradlew testDebugUnitTest                                 # pruebas extremo a extremo
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (~4,6 MB, firmado con la clave de depuración).
Resultados de las pruebas en `app/build/reports/`: `ggwave-roundtrip.md` (texto),
`imagenes.md` (imágenes), `compresion.md` y `compresion_por_mensaje.tsv` (compresión).
Muestras de imágenes: `LESVC_MUESTRAS=/ruta ./gradlew testDebugUnitTest` (por defecto `app/build/lesvc-img-samples/`).

> `assembleRelease` genera un APK **sin firmar**: para distribuirlo hace falta
> una clave de firma propia (no incluida).

## Instalar

- `adb install -r app-debug.apk`, o copiar el APK al móvil y abrirlo
  (permitir «instalar apps desconocidas»).
- Al pulsar «Empezar a escuchar» la app pide el permiso de micrófono.

## Uso

1. **Enviar**: escribe el texto (ñ, tildes y ¿¡ funcionan), elige el modo y el
   volumen, pulsa «▶ Enviar por sonido». El contador muestra caracteres, bytes
   (máx. 140 por envío), número de partes y duración aproximada.
2. **Recibir**: en el otro móvil, «🎤 Empezar a escuchar». Cada mensaje aparece
   arriba del historial con la hora. El silencio y el ruido no generan texto.
3. **Probar con un solo móvil**: activa «Oír también mis propios envíos», pulsa
   escuchar y luego enviar.
4. **Enviar imagen**: «🖼 Enviar imagen» → galería (selector de fotos del sistema, sin
   permisos) o cámara (sin permiso de cámara: la hace la app de cámara) → elige
   **Foto** o **Gráfico/dibujo** y el **tiempo máximo** (30 s, 1, 2 o 3 min). La vista
   previa muestra exactamente la imagen que llegará (se decodifican los mismos bytes),
   con bytes, partes y segundos para cada modo. «▶ Enviar esta imagen».
5. **Recibir imagen**: con «Empezar a escuchar» aparece «Recibiendo imagen 12/40», la
   barra de progreso y la lista de partes que faltan. Al completarse se comprueba el
   CRC32, se muestra en el historial y se guarda en **Imágenes/LESVC** (Android 10+ vía
   MediaStore, sin permisos; en Android 5–9 en la carpeta de imágenes de la app,
   `Android/data/com.lesvc.mesh/files/Pictures/LESVC`, también sin permisos).
6. **Si faltan partes**: el receptor pulsa «Pedir las que faltan (por sonido)»: emite una
   trama corta (≈1–2 s) con la lista; el emisor, que se pone a escuchar solo tras enviar
   una imagen, la oye y **reenvía esas partes automáticamente**. Alternativa manual: el
   emisor escribe la lista que le digan («3, 6-8») y pulsa «Reenviar partes que faltan».
   Opcional: «Repetir cada parte» (el doble de lento, aguanta cortes sin pedir nada).

## Mensajes largos (más de 140 bytes)

Se dividen automáticamente. Cada parte lleva 5 bytes de cabecera:
`0x1E` + ID de 2 caracteres + índice + (total−1) (base 36), y hasta 135 bytes de
texto, sin cortar nunca un carácter UTF-8. El receptor las une aunque lleguen
desordenadas o repetidas y descarta las incompletas a los 2 minutos.
Máximo 36 partes (~4.800 bytes). Los mensajes de ≤140 bytes van sin cabecera,
así que son compatibles con otras apps ggwave (p. ej. Waver).

## Imágenes: formato del envío

Todas las tramas empiezan por `0x1F` (un texto nunca empieza por un carácter de control):

| Trama | Contenido |
|---|---|
| `H` cabecera (20 B) | `1F 'H'` id(2) versión(1) formato(1: WebP/JPEG/PNG = tipo MIME) tamaño(4) partes(2) CRC32(4) ancho(2) alto(2) |
| `D` datos (≤140 B) | `1F 'D'` id(2) índice(2) partes(2) + hasta 132 bytes del fichero |
| `R` petición | `1F 'R'` id(2) flags(1: falta la cabecera) + rangos [inicio(2) cuántas(1)]… |

Orden: H, D0…Dn−1, H (la cabecera va al principio y al final). Binario puro (sin Base64;
probado con los 256 valores de byte). Límite: 1000 partes (~132 KB); una imagen típica
de 1–3 min son 10–25 partes.

**Recuperación elegida**: petición por sonido con la propia ggwave (misma corrección de
errores Reed-Solomon que los datos) + reenvío automático; es lo más simple y robusto
(no hay que dictar números) y la lista tecleada queda como respaldo.

**Compresión de imágenes** (en el móvil, `Bitmap.compress`):
- *Foto*: lado mayor 320→96 px, WebP con pérdidas (JPEG de respaldo), búsqueda binaria
  de la calidad; se elige el mayor tamaño con calidad ≥ 40; si no cabe, 96 px bajando
  calidad; opción blanco y negro.
- *Gráfico/dibujo*: paleta de 16/8/4 colores (o 1 bit) y PNG indexado o WebP sin
  pérdidas (el menor), desde 640 px; prioriza resolución sobre colores.

## Compresión de texto

Primer byte de la carga: `0x11` PPM, `0x12` URL, `0x13` conversacional, `0x14`
conversacional + bits, `0x1E` mensaje troceado, `0x1F` imagen; cualquier otro (≥ 0x20)
es UTF-8 normal (compatible con Waver). El emisor prueba todos los modos y manda el
más corto, **solo si así tarda menos** que el UTF-8; si no, va tal cual. Con
«Modo compatible» nunca se comprime.

- **PPM orden 4 + codificador de rango** (texto general): modelo estático entrenado con
  tres novelas de dominio público (Gutenberg) y un corpus de chat.
- **URL (LITERAL)**: un código combinado esquema × www × dominio de primer nivel +
  PPM entrenado con URLs. El esquema y el dominio llegan en minúsculas (el resto
  exacto); la app lo avisa en el contador.
- **Conversacional («jeroglíficos»)**: 200 palabras/frases con 1 byte, ~6.800 con 2
  bytes, mayúsculas y tildes sin pérdidas, referencias a palabras repetidas en el mismo
  mensaje (nombres propios) y, en `0x14`, códigos de longitud variable en bits
  (codificador de rango estático; tablas v1 con huella fijada en los tests).
- Diccionario **v1 congelado** en `assets/compresion/v1/`: cambiarlo obliga a usar
  otro id de cabecera (los móviles antiguos dirán «actualiza la app»).

## Modos y velocidad (medido, 48 kHz)

| Modo | Frecuencias | 140 bytes tardan | Velocidad aprox. |
|---|---|---|---|
| Audible normal | ~1,9–6,4 kHz | 13,6 s | ~10 B/s |
| Audible rápido | ~1,9–6,4 kHz | 9,3 s | ~15 B/s |
| Audible muy rápido | ~1,9–6,4 kHz | 5,0 s | ~28 B/s |
| Ultrasonido normal/rápido/muy rápido (experimental) | ~15–19,5 kHz | igual que su equivalente audible | igual |
| **Turbo (experimental, corta distancia)** – protocolo propio | ~1,9–7,9 kHz | 2,8 s | ~50 B/s |

Turbo es un protocolo personalizado de ggwave (2 tramas y 4 bytes por símbolo) que solo
entienden móviles con la 0.3 o posterior. Simulación con ruido, eco y deriva de reloj en
`tools/turbo/RESULTADOS.md`; falta probarlo en móviles reales.

## Detalles técnicos que importan

- ggwave-v0.4.3 solo decodifica bien si recibe **tramas de exactamente 1024
  muestras**; `GGWave.alimentar()` agrupa el audio del micro en tramas así.
- Su remuestreo interno falla con tramas pequeñas; por eso si el micro graba a
  44,1 kHz usamos `Remuestreador` (sinc con ventana) para pasar a 48 kHz.
- El receptor solo tiene activos los 6 protocolos de la app (menos falsos positivos).
- Fuente de micro: «sin procesar» si el móvil lo admite; si no, la de
  reconocimiento de voz; si no, la estándar.

## Problemas típicos

| Problema | Solución |
|---|---|
| `NDK not configured` / `CMake not found` | Instala `ndk;26.1.10909125` y `cmake;3.22.1` en el SDK |
| No se recibe nada | Sube el volumen multimedia del emisor, acerca los móviles (30 cm–1 m), usa «Audible normal» |
| «Se oyó una transmisión pero no se pudo descifrar» | Ruido o eco: acércalos o baja la velocidad |
| Ultrasonido no funciona | Normal: muchos altavoces/micros no llegan a 15–19 kHz. Usa un modo audible |
| Tests: `cmake not found` | Instala `cmake` o el paquete `cmake;3.22.1` del SDK |
