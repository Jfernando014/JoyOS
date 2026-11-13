# JoyOS Production v1.1.0 — Automotive Home Launcher & Hardware DSP OS

[![Build JoyOS Launcher APK](https://github.com/Jfernando014/JoyOS/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Jfernando014/JoyOS/actions/workflows/build-apk.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_8.1%2B_%7C_SDK_26--34-brightgreen.svg)]()
[![Hardware Target](https://img.shields.io/badge/Target-1024%C3%97600_AC8227L_9%22-blue.svg)]()

Sistema operativo de cabina y lanzador de inicio nativo (`HOME` Launcher Android) desarrollado para pantallas táctiles automotrices de 9 pulgadas (`1024×600`, MediaTek `AC8227L` / `ZXDZ_01`, MCU `CVD1810-WJ`, panel `YT090IBNK12018`) en Chevrolet Joy.

Versión oficial de producción `v1.1.0` con **Mapa Interactivo Leaflet + Rutas GPS OSRM**, **Salvapantallas Automotriz (Digital / Analógico / OLED Puro / Foto Personalizada)**, **Más Ajustes completos con PIN Valet táctil, Permisos Android, Ventilador Térmico y Copias de Seguridad JSON**, telemetría real OBD2 BLE + TPMS USB, **Ecualizador Hardware DSP de 5 bandas**, **Cajón de Aplicaciones**, **Gestor de Archivos USB** y **Actualizaciones OTA vía GitHub Releases**.

---

## 📲 Descarga Directa del APK (1 Toque)

Haz clic en el siguiente enlace desde tu celular o radio Android para descargar directamente el archivo `JoyOSLauncher.apk` de la última versión publicada:

👉 **[📥 Descargar JoyOSLauncher.apk (Última Release)](https://github.com/Jfernando014/JoyOS/releases/latest/download/JoyOSLauncher.apk)**

> [!TIP]
> También puedes consultar el historial de versiones y notas de cambio en **[Releases de JoyOS](https://github.com/Jfernando014/JoyOS/releases)**. La rama `dev` ejecuta compilaciones automáticas de prueba en GitHub Actions y solo los merges/tags en `main` publican nuevas *Releases* OTA.

---

## ⚡ Super Optimización de Hardware 100% In-App (Sin PC ni cables)

No necesitas conectar tu laptop a la tablet ni usar cables USB especiales:
1. **Auto-Optimización al Encender:** 4 segundos después de arrancar el vehículo (o manualmente en 1 toque), JoyOS puede ejecutar su optimizador nativo.
2. **Botón Manual de 1 Toque:** En *Ajustes → Más Ajustes → Sistema, OTA y Respaldo*, toca **`[ ⚡ Optimizar Hardware al 100% ]`**.
3. **¿Qué realiza internamente?**
   - Desbloquea el limitador de audio de fábrica (`audio_safe_volume_state = 2`).
   - Escala las animaciones a `0.5x` para máxima fluidez y respuesta táctil instantánea.
   - Si la tablet tiene acceso Root de fábrica (MediaTek `AC8227L`), congela launchers OEM (`launcher3`, `fyt`, `ts`, `xyauto`, `autochips`) liberando RAM y ciclos de CPU.
   - Habilita el puerto ADB inalámbrico `5555` en segundo plano.

*(Opcional: Si prefieres ejecutarlo externamente desde un PC por ADB, puedes seguir usando `scripts/optimize_joy_tablet.bat` o `./scripts/optimize_joy_tablet.sh`)*.

---

## 🏛️ Arquitectura del Repositorio

El proyecto sigue una arquitectura desacoplada entre la capa visual web (`joy_os_v1_production.html` ↔ `app/src/main/assets/www/index.html`, 100% compatible offline en WebView Android 8.1) y los servicios nativos de hardware en Kotlin:

```text
JoyOS/
├── .github/workflows/
│   └── build-apk.yml             # Compilación en CI (Gradle + JDK 17): artefacto en dev/main, Release OTA en main
├── app/
│   ├── build.gradle.kts          # Módulo Android (compileSdk 34, minSdk 26, versionCode 13, v1.1.0)
│   ├── proguard-rules.pro        # Reglas keep para @JavascriptInterface y bridge
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # Intent filters de HOME Launcher, USB TPMS y permisos vehiculares
│   │   ├── java/com/joyos/launcher/
│   │   │   ├── MainActivity.kt   # WebView hardware-accelerated, receptor multimedia AVRCP y ciclo de vida
│   │   │   ├── bridge/
│   │   │   │   └── AndroidBridge.kt         # Puente JS <-> Android (Apps, DSP, GPS, permisos, caché, backups)
│   │   │   └── services/
│   │   │       ├── GpsTelemetryManager.kt   # Velocidad GPS real (km/h), fuente Híbrida/Tablet/Tel y Trip
│   │   │       ├── JoyAudioDspEngine.kt     # DSP nativo (Equalizer 5 bandas, BassBoost, Loudness, Virtualizer)
│   │   │       ├── JoyHardwareOptimizer.kt  # Optimizador in-app RAM/animaciones/audio/ADB
│   │   │       ├── ObdBleManager.kt         # Dual-mode Classic SPP + BLE GATT para ELM327 / Vgate iCar Pro
│   │   │       ├── OtaUpdateManager.kt      # Buscador OTA y descarga de APK / Hot-Update HTML
│   │   │       └── TpmsUsbReceiver.kt       # Decodificador USB-Serial 433.92 MHz (Jansite/CH340/CP210x/FTDI)
│   │   ├── res/                  # Tema oscuro automotriz, FileProvider paths y filtros USB
│   │   └── assets/www/           # Bundle frontend autónomo (100% offline en radio)
│   │       ├── index.html        # Espejo exacto de joy_os_v1_production.html
│   │       ├── css/fonts.css     # Fuentes Sora y Manrope locales en WOFF2
│   │       ├── css/leaflet.css   # Estilos locales del mapa Leaflet
│   │       ├── fonts/            # Archivos binarios tipográficos offline
│   │       └── js/leaflet.js     # Motor cartográfico local Leaflet
├── scripts/
│   ├── optimize_joy_tablet.bat   # Script automatizado de optimización/ADB para Windows
│   └── optimize_joy_tablet.sh    # Script automatizado de optimización/ADB para Linux/macOS
└── joy_os_v1_production.html     # Archivo fuente principal para pruebas directas en navegador y APK
```

---

## 🧩 Componentes y Funcionalidades

### 1. Capa Visual & Experiencia de Usuario (`joy_os_v1_production.html` / `assets/www/`)
- **Luminous Bottom Dock:** Barra inferior con tipografía *Manrope* (`Inicio`, `Cockpit`, `Música`, `Mapa`, `Ajustes`, `::`).
- **Mapa Interactivo Leaflet + Rutas OSRM:** 4 estilos visuales (Oscuro, Noche / Asfalto, Satélite, Claro), buscador de destinos, favoritos, historial, zoom táctil fluido (`L.canvas()` + `updateWhenZooming: false` + `fadeAnimation: false`) y píldora flotante de música en pantalla completa.
- **3 Modos de Velocímetro (`km/h` o `mph`):** *Need for Speed Most Wanted*, *Arco Minimalista* y *Sport Chrono*, sincronizados en Inicio, Cockpit y Mapa.
- **Salvapantallas Automotriz Inteligente:** 4 fondos (incluyendo *OLED Puro*), foto personalizada por URL o galería local, reloj digital/analógico SVG en vivo (`12h` / `24h`), desplazamiento anti-quemado, atenuación nocturna, tira de velocidad/navegación y mini-reproductor sin cerrar la vista.
- **Más Ajustes + Seguridad Valet:** PIN Valet con teclado numérico táctil, modales glassmórficos sin bloqueos nativos, gestión de permisos Android, control de ventilador térmico, odómetro Trip, unidades métricas/imperiales/bar, exportación/importación JSON de configuración, limpieza de caché e inspector REA.
- **Perfiles de Conductor, Cajón de Apps y Documentos R5:** Previsualización en tiempo real de temas (*Dark/Light*) y 5 acentos (*Cyan, Violeta, Ámbar, Verde, Rojo RS*), documentos del vehículo (SOAT, Tecno, Licencia, Pico y Placa) y gestor USB OTG con instalador APK.
- **Panel de Diagnóstico Oculto:** Dando **5 toques consecutivos en el corbatín Chevrolet** superior izquierdo, se abre el simulador de señales vehiculares.

### 2. Capa Nativa en Kotlin (`com.joyos.launcher`)
- [`MainActivity.kt`](app/src/main/java/com/joyos/launcher/MainActivity.kt): Bloquea barras del sistema con `WindowInsetsController`, gestiona `FLAG_KEEP_SCREEN_ON` y timeout de pantalla, captura eventos AVRCP Bluetooth del radio y configura el WebView acelerado por hardware.
- [`AndroidBridge.kt`](app/src/main/java/com/joyos/launcher/bridge/AndroidBridge.kt): Expone `@JavascriptInterface window.AndroidBridge` para lanzar apps (`PackageManager`), controlar volumen/radio/DSP, gestionar permisos Android, leer sensores/red/RAM/almacenamiento USB, limpiar caché y respaldar/restaurar ajustes JSON.
- [`GpsTelemetryManager.kt`](app/src/main/java/com/joyos/launcher/services/GpsTelemetryManager.kt): Suscrito a GPS hardware / red híbrida para calcular velocidad en `km/h` y distancia `Trip` a `2 Hz`.
- [`ObdBleManager.kt`](app/src/main/java/com/joyos/launcher/services/ObdBleManager.kt): Conecta vía BT Classic SPP o BLE GATT a escáneres ELM327 (`Vgate iCar Pro`) leyendo RPM, temperatura de refrigerante, carga y voltaje.
- [`TpmsUsbReceiver.kt`](app/src/main/java/com/joyos/launcher/services/TpmsUsbReceiver.kt): Decodifica presión PSI y temperatura de llantas vía dongle USB OTG con alertas configurables.
- [`OtaUpdateManager.kt`](app/src/main/java/com/joyos/launcher/services/OtaUpdateManager.kt): Verifica *GitHub Releases* para actualizar tanto el APK como parches Hot-Update HTML.

---

## 🧪 Cómo Probar JoyOS sin la Pantalla del Carro

### Opción A: En tu Computador (Navegador Web)
1. Abre [`joy_os_v1_production.html`](joy_os_v1_production.html) (o [`app/src/main/assets/www/index.html`](app/src/main/assets/www/index.html)) en Chrome, Edge o Brave.
2. Agrega `?prod=1` al final de la URL en la barra de direcciones:
   ```text
   file:///.../joy_os_v1_production.html?prod=1
   ```
3. Presiona **F12** y activa el modo de dispositivo (`Ctrl + Shift + M`).
4. Configura el tamaño de pantalla en **`1024 × 600`**.

### Opción B: En cualquier Celular Android
1. Descarga el archivo `JoyOSLauncher-APK` desde [GitHub Actions](https://github.com/Jfernando014/JoyOS/actions/workflows/build-apk.yml).
2. Descomprime e instala `app-debug.apk` en tu teléfono.
3. Ábrelo como cualquier aplicación; se ejecutará en horizontal a pantalla completa.

### Opción C: En un Emulador de PC (LDPlayer / BlueStacks)
1. Instala un emulador Android para Windows.
2. Configura una resolución personalizada de tablet: **`1024 × 600` (160 DPI)**.
3. Arrastra y suelta el archivo `app-debug.apk` sobre el emulador para instalarlo y probar la experiencia táctil idéntica al vehículo.

---

## 🚗 Instalación en la Radio del Vehículo (`ZXDZ_01`)

1. Copia el archivo `app-debug.apk` a una memoria USB (formato FAT32).
2. Conecta la USB a cualquiera de los puertos del arnés en la guantera.
3. En la radio, abre el explorador de archivos nativo, toca el `.apk` y pulsa **Instalar**.
4. Presiona el botón físico o táctil de **Inicio (Home)** de la radio y selecciona **JoyOS** -> **"Siempre"** como lanzador predeterminado.
