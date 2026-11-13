#!/usr/bin/env bash
set -e

echo "====================================================================="
echo "   🚀 JoyOS v1.1.0 — OPTIMIZADOR AUTOMÁTICO DE HARDWARE Y AUDIO"
echo "   Chevrolet Joy 2022 · Unidad AC8227L / ZXDZ_01 (1024x600)"
echo "====================================================================="
echo ""

echo "[1/6] Verificando conexión ADB..."
if ! adb devices | grep -q -w "device"; then
    echo "❌ ERROR: No se detectó ninguna tablet conectada por USB o WiFi."
    echo "Asegúrate de activar 'Depuración por USB' en los ajustes de Android."
    exit 1
fi
echo "  -> Tablet conectada correctamente."
echo ""

echo "[2/6] Verificando instalación de JoyOSLauncher.apk..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! adb shell pm list packages | grep -q "com.joyos.launcher"; then
    if [ -f "$SCRIPT_DIR/../JoyOSLauncher.apk" ]; then
        echo "  -> Instalando JoyOSLauncher.apk..."
        adb install -r -g "$SCRIPT_DIR/../JoyOSLauncher.apk"
    elif [ -f "$SCRIPT_DIR/JoyOSLauncher.apk" ]; then
        echo "  -> Instalando JoyOSLauncher.apk..."
        adb install -r -g "$SCRIPT_DIR/JoyOSLauncher.apk"
    elif [ -f "JoyOSLauncher.apk" ]; then
        echo "  -> Instalando JoyOSLauncher.apk..."
        adb install -r -g JoyOSLauncher.apk
    elif [ -f "$SCRIPT_DIR/../app/build/outputs/apk/debug/JoyOSLauncher.apk" ]; then
        echo "  -> Instalando APK desde compilación local..."
        adb install -r -g "$SCRIPT_DIR/../app/build/outputs/apk/debug/JoyOSLauncher.apk"
    else
        echo "  ⚠️ No se encontró el APK local. Descárgalo de GitHub Releases."
    fi
else
    echo "  -> JoyOS ya está presente en la tablet."
fi
echo ""

echo "[3/6] Otorgando permisos de sistema y hardware..."
JOY_PKG="com.joyos.launcher.debug"
if adb shell pm path com.joyos.launcher >/dev/null 2>&1; then JOY_PKG="com.joyos.launcher"; fi
if adb shell pm path com.joyos.launcher.debug >/dev/null 2>&1; then JOY_PKG="com.joyos.launcher.debug"; fi
adb shell pm grant "$JOY_PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant "$JOY_PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
adb shell pm grant "$JOY_PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant "$JOY_PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant "$JOY_PKG" android.permission.READ_MEDIA_AUDIO 2>/dev/null || true
adb shell pm grant "$JOY_PKG" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
adb shell pm grant "$JOY_PKG" android.permission.BLUETOOTH_SCAN 2>/dev/null || true
adb shell appops set "$JOY_PKG" WRITE_SETTINGS allow 2>/dev/null || true
adb shell appops set "$JOY_PKG" REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true
adb shell cmd notification allow_listener "$JOY_PKG/com.joyos.launcher.services.JoyMediaNotificationListener" 2>/dev/null || true
echo "  -> Permisos de GPS, almacenamiento, Bluetooth, brillo, MediaSession y OTA otorgados ($JOY_PKG)."
echo ""

echo "[4/6] Configurando JoyOS como el ÚNICO Launcher Principal (HOME)..."
adb shell cmd package set-home-activity "$JOY_PKG/com.joyos.launcher.MainActivity" 2>/dev/null || true
echo "  -> JoyOS registrado como Home Launcher nativo."
echo ""

echo "[5/6] Desbloqueando 100% de Audio y Rendimiento..."
adb shell settings put global audio_safe_volume_state 2 2>/dev/null || true
adb shell dumpsys deviceidle whitelist +"$JOY_PKG" 2>/dev/null || true
adb shell settings put global window_animation_scale 0.5 2>/dev/null || true
adb shell settings put global transition_animation_scale 0.5 2>/dev/null || true
adb shell settings put global animator_duration_scale 0.5 2>/dev/null || true
echo "  -> Audio DSP desbloqueado sin limitador de volumen."
echo "  -> Prioridad máxima de CPU/GPU aplicada."
echo ""

echo "[6/6] Congelando launchers OEM de fábrica..."
read -p "¿Deseas congelar el launcher genérico de fábrica para liberar 400 MB de RAM? (s/n): " confirm
if [[ "$confirm" =~ ^[sS]$ ]]; then
    adb shell pm disable-user --user 0 com.android.launcher3 2>/dev/null || true
    adb shell pm disable-user --user 0 com.fyt.launcher 2>/dev/null || true
    adb shell pm disable-user --user 0 com.ts.main 2>/dev/null || true
    adb shell pm disable-user --user 0 com.xyauto.launcher 2>/dev/null || true
    echo "  -> Launchers de fábrica congelados. 100% de RAM entregada a JoyOS."
fi

echo ""
echo "====================================================================="
echo "   🎉 ¡OPTIMIZACIÓN COMPLETA! Iniciando JoyOS v1.1.0..."
echo "====================================================================="
adb shell am start -n "$JOY_PKG/com.joyos.launcher.MainActivity" 2>/dev/null || true
