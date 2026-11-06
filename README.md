# JoyOS v8.3 — Automotive Home Launcher & Hardware DSP OS

[![Build JoyOS Launcher APK](https://github.com/Jfernando014/JoyOS/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Jfernando014/JoyOS/actions/workflows/build-apk.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_8.1%2B_%7C_SDK_26--34-brightgreen.svg)]()
[![Hardware Target](https://img.shields.io/badge/Target-1280%C3%97720_AC8227L_9%22-blue.svg)]()

Sistema operativo de cabina y lanzador de inicio nativo (Android Home Launcher) desarrollado para pantallas táctiles de 9 pulgadas (`1280×720`, MediaTek `AC8227L`, MCU `CVD1810-WJ`, panel `YT090IBNK12018`) en Chevrolet Joy.

Combina telemetría en tiempo real por GPS, conectividad Plug & Play para escáner OBD2 Bluetooth y sensores TPMS USB, **Ecualizador Gráfico Hardware DSP de 5 bandas**, **Cajón de Aplicaciones 100% Real**, **Gestor de Archivos USB para la Guantera**, **Salidas de Emergencia y Acceso Directo a Ajustes de Android**, y optimización de rendimiento automotriz sin datos simulados ni dependencias de internet.

---

## 📲 Descarga Directa del APK (1 Toque)

Haz clic en el siguiente enlace desde tu celular o radio Android para descargar directamente el archivo `JoyOSLauncher.apk` (sin iniciar sesión en GitHub ni descomprimir archivos ZIP):

👉 **[📥 Descargar JoyOSLauncher.apk v8.3.0 (Instalador Directo)](https://github.com/Jfernando014/JoyOS/releases/download/v8.3.0/JoyOSLauncher.apk)**

> [!TIP]
> También puedes ver las versiones publicadas y notas de versión en **[Releases de JoyOS](https://github.com/Jfernando014/JoyOS/releases)**.

---

## ⚡ Super Optimización de Hardware 100% In-App (Sin PC ni cables)

A partir de JoyOS v8.2, no necesitas conectar tu laptop a la tablet ni usar cables USB especiales:
1. **Auto-Optimización al Encender:** 4 segundos después de arrancar el carro, JoyOS ejecuta de forma silenciosa el optimizador nativo en segundo plano.
2. **Botón Manual de 1 Toque:** En *Ajustes → Información del Sistema*, toca **`[ ⚡ Optimizar Hardware al 100% (Super Mode) ]`**.
3. **¿Qué realiza internamente?**
   - Desbloquea el limitador de audio de fábrica (`audio_safe_volume_state = 2`).
   - Escala las animaciones a `0.5x` para máxima fluidez y respuesta táctil instantánea.
   - Si la tablet tiene acceso Root de fábrica (MediaTek AC8227L), congela de inmediato los launchers OEM (`launcher3`, `fyt`, `ts`, `xyauto`) ahorrando ~400 MB de RAM y liberando ciclos de CPU.
   - Habilita el puerto ADB inalámbrico `5555` en segundo plano.

*(Opcional: Si prefieres ejecutarlo externamente desde un PC por ADB, puedes seguir usando `scripts/optimize_joy_tablet.bat` o `./scripts/optimize_joy_tablet.sh`)*.

---

## 🏛️ Arquitectura del Repositorio

El proyecto sigue una estructura desacoplada entre la capa visual web (100% offline) y los servicios nativos del hardware automotriz en Kotlin:

```text
JoyOS/
├── .github/workflows/
│   └── build-apk.yml             # Compilación automatizada en CI con Gradle 8.5 y JDK 17
├── app/
│   ├── build.gradle.kts          # Módulo Android (SDK 34, minSdk 26, ProGuard rules, v8.0.0)
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # Intent filters de HOME Launcher y permisos vehiculares
│   │   ├── java/com/joyos/launcher/
│   │   │   ├── MainActivity.kt   # Actividad inmersiva a pantalla completa y ciclo de vida
│   │   │   ├── bridge/
│   │   │   │   └── AndroidBridge.kt       # Puente JavaScript <-> Android (Apps, DSP, volumen, RAM)
│   │   │   └── services/
│   │   │       ├── JoyAudioDspEngine.kt   # Motor DSP hardware (Equalizer, BassBoost, Loudness, Virtualizer)
│   │   │       ├── GpsTelemetryManager.kt # Velocidad real GPS (km/h) y odómetro a 2 Hz
│   │   │       ├── ObdBleManager.kt       # Conexión continua a Vgate iCar Pro 4.0 (ELM327 CAN)
│   │   │       └── TpmsUsbReceiver.kt     # Monitoreo de presión de llantas por USB OTG
│   │   ├── res/                  # Tema oscuro automotriz y vector adaptive icon
│   │   └── assets/www/           # Bundle frontend autónomo (100% offline)
│   │       ├── index.html        # Interfaz de producción (100vw × 100vh sin marcos)
│   │       ├── css/fonts.css     # Fuentes Sora y Manrope locales en WOFF2
│   │       ├── fonts/            # Archivos binarios tipográficos offline
│   │       └── js/tailwindcss.min.js # Motor de utilidades local
├── scripts/
│   ├── optimize_joy_tablet.bat   # Script automatizado de optimización para Windows
│   └── optimize_joy_tablet.sh    # Script automatizado de optimización para Linux/macOS
└── prototype_v8_joy.html         # Prototipo interactivo con simulador de señales para navegador
```

---

## 🧩 Componentes y Funcionalidades

### 1. Capa Visual & Experiencia de Usuario (`assets/www/`)
- **Luminous Bottom Dock:** Menú rasante con tipografía *Manrope* (`Inicio`, `Cockpit`, `Música`, `Mapa`, `Ajustes`, `::`).
- **Navegación Global por Gestos:** Desliza a la izquierda o derecha en cualquier pantalla para alternar de forma continua entre todas las vistas.
- **3 Modos de Velocímetro:** *Need for Speed Most Wanted* (aguja análoga), *Arco Minimalista* y *Sport Chrono* (cuadrante de competición).
- **Modo Inteligente Sin Lector:** Si no hay escáner OBD2 ni TPMS conectados, el sistema simplifica las agujas para mostrar únicamente velocidad limpia por GPS (`km/h`) y voltaje de arnés ACC (`14,1 V`).
- **Perfiles de Conductor Estilo Streaming:** Selección de conductor al encender con previsualización en tiempo real de temas (*Dark/Light*) y 5 colores de acento (*Cyan, Cobalt, Amber, Emerald, Rose*).
- **Cajón de Aplicaciones (`::`) y Gestor de Archivos USB:** Acceso directo a USB OTG con instalador de APKs dinámico y función de **Hot-Update en 1 toque** para actualizar la interfaz sin reinstalar la aplicación nativa.
- **Panel de Diagnóstico Oculto:** Dando **5 toques consecutivos en el corbatín Chevrolet** superior izquierdo, se activa el simulador flotante de señales vehiculares.

### 2. Capa Nativa en Kotlin (`com.joyos.launcher`)
- [`MainActivity.kt`](app/src/main/java/com/joyos/launcher/MainActivity.kt): Bloquea barras del sistema con `WindowInsetsController`, desactiva el botón atrás para evitar cierres accidentales de la pantalla de inicio y activa aceleración por hardware (`LAYER_TYPE_HARDWARE`).
- [`AndroidBridge.kt`](app/src/main/java/com/joyos/launcher/bridge/AndroidBridge.kt): Expone métodos a JavaScript (`window.AndroidBridge`) para consultar aplicaciones instaladas con `PackageManager`, lanzar apps de navegación (`Waze`, `ZLink`, `Spotify`), controlar el volumen maestro con `AudioManager` y aplicar parches en vivo.
- [`GpsTelemetryManager.kt`](app/src/main/java/com/joyos/launcher/services/GpsTelemetryManager.kt): Suscrito al proveedor GPS de la antena externa para calcular velocidad real en `km/h` y acumular la distancia del viaje.
- [`ObdBleManager.kt`](app/src/main/java/com/joyos/launcher/services/ObdBleManager.kt): Enlaza en segundo plano con adaptadores Bluetooth 4.0 (*Vgate iCar Pro 4.0*) ejecutando comandos `010C` (RPM), `0105` (Temperatura refrigerante) y `ATRV` (Voltaje).
- [`TpmsUsbReceiver.kt`](app/src/main/java/com/joyos/launcher/services/TpmsUsbReceiver.kt): Decodifica la presión de las 4 ruedas recibidas por el dongle USB y dispara alertas automáticas si la presión cae de `26 PSI`.

---

## 🧪 Cómo Probar JoyOS sin la Pantalla del Carro

### Opción A: En tu Computador (Navegador Web)
1. Abre [app/src/main/assets/www/index.html](app/src/main/assets/www/index.html) en Chrome, Edge o Brave.
2. Agrega `?prod=1` al final de la URL en la barra de direcciones:
   ```text
   file:///.../app/src/main/assets/www/index.html?prod=1
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
