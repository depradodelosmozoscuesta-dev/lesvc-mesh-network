# 📱 LESVC Mesh Audio – Cómo se compila (versión real, 0.2)

Mensajes de texto por sonido entre móviles Android, sin internet, usando
**ggwave** (C/C++, MIT) a través de JNI.

---

## Qué hay en el proyecto

```
settings.gradle, build.gradle, gradle.properties, gradlew(.bat), gradle/wrapper/   → Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24
app/build.gradle                       → com.lesvc.mesh, versionCode 2 / 0.2, minSdk 21, target 34,
                                          NDK 26.1.10909125, CMake 3.22.1, ABIs arm64-v8a / armeabi-v7a / x86_64
app/src/main/cpp/
    CMakeLists.txt                     → compila ggwave (estático) + liblesvc_ggwave.so (JNI)
    ggwave_jni.cpp                     → puente JNI (init / free / encode / decode, audio mono PCM16)
    ggwave/                            → ggwave-v0.4.3 (a38e38b) SIN modificar + LICENSE + VERSION.txt
app/src/main/kotlin/com/lesvc/mesh/
    MainActivity.kt                    → interfaz en español
    audio/GGWave.kt                    → envoltorio Kotlin (agrupa el audio en tramas de 1024 muestras)
    audio/Protocolo.kt                 → 6 modos: audible normal/rápido/muy rápido, ultrasonido ×3
    audio/Troceador.kt                 → UTF-8, límite 140 bytes, troceo y reensamblado
    audio/Emisor.kt                    → AudioTrack mono 48 kHz (streaming)
    audio/Receptor.kt                  → AudioRecord mono continuo → ggwave
    audio/Remuestreador.kt             → 44,1 → 48 kHz para micros que no graban a 48 kHz
app/src/main/assets/licenses/          → licencias MIT mostradas en «Acerca de»
app/src/test/…                         → pruebas JVM extremo a extremo (sin móvil)
tools/build-host-jni.sh                → compila el mismo código nativo para el PC (lo usan los tests)
legacy/sonic-stereo/                   → el codificador antiguo, fuera de la compilación
```

## Requisitos

- JDK 17 o superior (probado con OpenJDK 21)
- Android SDK con: `platforms;android-34`, `build-tools;34.0.0`,
  **`ndk;26.1.10909125`** y **`cmake;3.22.1`**
  (`sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`; Android Studio los instala solo)
- Para los tests en el PC: compilador C++ (g++ o clang) – en Linux/macOS

## Compilar

```bash
git clone https://github.com/depradodelosmozoscuesta-dev/lesvc-mesh-network.git
cd lesvc-mesh-network
echo "sdk.dir=/ruta/a/Android/sdk" > local.properties     # o exporta ANDROID_HOME
./gradlew assembleDebug                                     # APK de depuración
./gradlew testDebugUnitTest                                 # pruebas extremo a extremo
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (~3,8 MB, firmado con la clave de depuración).
Tabla de resultados de las pruebas: `app/build/reports/ggwave-roundtrip.md`.

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

## Mensajes largos (más de 140 bytes)

Se dividen automáticamente. Cada parte lleva 5 bytes de cabecera:
`0x1E` + ID de 2 caracteres + índice + (total−1) (base 36), y hasta 135 bytes de
texto, sin cortar nunca un carácter UTF-8. El receptor las une aunque lleguen
desordenadas o repetidas y descarta las incompletas a los 2 minutos.
Máximo 36 partes (~4.800 bytes). Los mensajes de ≤140 bytes van sin cabecera,
así que son compatibles con otras apps ggwave (p. ej. Waver).

## Modos y velocidad (medido, 48 kHz)

| Modo | Frecuencias | 140 bytes tardan | Velocidad aprox. |
|---|---|---|---|
| Audible normal | ~1,9–6,4 kHz | 13,6 s | ~10 B/s |
| Audible rápido | ~1,9–6,4 kHz | 9,3 s | ~15 B/s |
| Audible muy rápido | ~1,9–6,4 kHz | 5,0 s | ~28 B/s |
| Ultrasonido normal/rápido/muy rápido (experimental) | ~15–19,5 kHz | igual que su equivalente audible | igual |

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
