# 📱 LESVC Audio Messaging - Guía de Compilación para Groc

## Objetivo
Compilar la aplicación Android y generar un **APK instalable** listo para usar en teléfono.

---

## 📋 Requisitos
- Android Studio (última versión)
- JDK 11+
- SDK Android 21+ instalado
- 4GB RAM mínimo
- Conexión a Internet

---

## 🚀 Pasos de Compilación

### 1. Clonar el Repositorio
```bash
git clone https://github.com/depradodelosmozoscuesta-dev/lesvc-mesh-network.git
cd lesvc-mesh-network
```

### 2. Abrir en Android Studio
```bash
# En la carpeta del proyecto:
open . # En Mac
start . # En Windows
# O abre Android Studio manualmente y selecciona la carpeta
```

### 3. Sincronizar Gradle
- Android Studio pedirá sincronizar → Click en "Sync Now"
- Espera a que descargue dependencias (2-5 minutos)

### 4. Compilar la Aplicación
```bash
# Opción A: Desde Android Studio
Build > Make Project

# Opción B: Desde terminal (más rápido)
./gradlew build

# Opción C: Generar APK directamente
./gradlew assembleRelease
```

### 5. Localizar el APK
El APK compilado estará en:
```
app/build/outputs/apk/release/app-release.apk
```

O si compilas debug:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📦 Instalación en Teléfono

### Vía USB (Recomendado)
1. Conecta teléfono Android al PC por USB
2. Activa "Depuración USB" en Configuración > Desarrollador
3. En Android Studio: `Run > Run 'app'`
4. Selecciona tu teléfono
5. La app se instala automáticamente

### Vía APK Manual
1. Copia `app-release.apk` al teléfono
2. Abre el APK en el teléfono
3. Android pide permisos → Acepta
4. App instalada ✓

---

## 🔧 Configuración de Permisos

**Ya está configurado en AndroidManifest.xml:**
- ✅ `RECORD_AUDIO` - Para grabar audio
- ✅ `INTERNET` - (opcional, para futuras mejoras)

**Cuando ejecutes la app en teléfono:**
- Aparecerá dialogo pidiendo "Permiso de micrófono"
- Acepta para que funcione la grabación

---

## 📱 Uso de la App

### Interfaz
```
┌─────────────────────────────┐
│   Audio Messaging Estéreo   │
│  Codificación Sónica: 14    │
├─────────────────────────────┤
│  [Cuadro de texto]          │
│  "Ingresa texto aquí"       │
├─────────────────────────────┤
│  ▶ Reproducir | ⏺ Grabar | ✕ Limpiar
├─────────────────────────────┤
│  Estado: listo              │
├─────────────────────────────┤
│  L: (canal izquierdo)       │
│  R: (canal derecho)         │
└─────────────────────────────┘
```

### Funciones
1. **▶ Reproducir**: 
   - Escribe texto en el cuadro
   - Click en "Reproducir"
   - La app genera audio estéreo y lo reproduce

2. **⏺ Grabar**: 
   - Click en "Grabar"
   - Habla cerca del micrófono o reproduce audio
   - Después de 10 segundos se detiene automáticamente
   - Muestra lo decodificado en Canal L y R

3. **✕ Limpiar**: 
   - Limpia todo para empezar de nuevo

---

## 🔊 Características Técnicas

### Codificación de Caracteres
Cada letra/número = 1 símbolo sónico con:
- **Nota musical** (C3-B3, 12 notas)
- **Duración** (40-60ms por canal)
- **Amplitud** (0.5-0.9 volumen relativo)
- **Repeticiones** (1-2 pulsos para redundancia)

**Caracteres soportados:**
- A-Z (26 letras)
- 0-9 (10 dígitos)
- Espacio, punto, coma, !, ? (9 caracteres especiales)
- **Total: 45 caracteres**

### Decodificación
- FFT (Transformada Rápida de Fourier) 256-bins
- Detección de nota dominante
- Análisis de duración y amplitud
- Matching con diccionario

---

## 🐛 Troubleshooting

| Problema | Solución |
|----------|----------|
| "Gradle sync failed" | Click "File > Invalidate Caches > Invalidate and Restart" |
| "SDK not found" | Abre SDK Manager (Tools > SDK Manager) y descarga API 21+ |
| "No device found" | Conecta teléfono USB, activa Debug Mode |
| "Permission denied" | Acepta permisos en el teléfono cuando aparezcan |
| "Audio no se escucha" | Sube volumen del teléfono, verifica altavoz no está silenciado |
| "Decodificación fallida" | Acerca micrófono más, reduce ruido de fondo |

---

## 📊 Versiones del APK

| Tipo | Tamaño | Uso | Compilación |
|------|--------|-----|-------------|
| **Debug** | ~3MB | Desarrollo/Testing | `./gradlew assembleDebug` (rápido) |
| **Release** | ~2.5MB | Distribución final | `./gradlew assembleRelease` (optimizado) |

**Para el usuario final: usa Release**

---

## ✅ Checklist Compilación

- [ ] Repositorio clonado
- [ ] Android Studio abierto
- [ ] Gradle sincronizado
- [ ] Compilación exitosa (sin errores)
- [ ] APK generado en `app/build/outputs/apk/`
- [ ] Teléfono conectado o APK transferido
- [ ] App instalada
- [ ] Permisos de audio aceptados
- [ ] Test: Escribe texto → Reproducir → Escuchas audio
- [ ] Test: Grabar → Decodifica el texto

---

## 📞 Soporte
Si hay errores de compilación, envía:
1. El error exacto de Gradle
2. Versión de Android Studio
3. JDK version: `java -version`
4. Sistema operativo

---

## 🎯 Resultado Final
**APK ejecutable** listo para instalar en cualquier teléfono Android 5.0+ (API 21+)

Tamaño: ~2.5MB | Instalación: 5-10 segundos | Funcionamiento: Inmediato ✓
