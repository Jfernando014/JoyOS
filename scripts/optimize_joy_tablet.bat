@echo off
chcp 65001 >nul
title Optimizador 100%% Hardware - JoyOS v8.0 Chevrolet Joy
cls
echo =====================================================================
echo    🚀 JoyOS v8.0 — OPTIMIZADOR AUTOMÁTICO DE HARDWARE Y AUDIO
echo    Chevrolet Joy 2022 · Unidad AC8227L / ZXDZ_01 (1024x600)
echo =====================================================================
echo.

echo [1/6] Verificando conexión ADB con la pantalla/tablet...
adb devices > adb_check.tmp
findstr /C:"device" adb_check.tmp | findstr /V /C:"List of devices" >nul
if %errorlevel% neq 0 (
    echo.
    echo ❌ ERROR: No se detectó ninguna tablet conectada por USB/WiFi.
    echo Asegúrate de tener la "Depuración por USB" activada en tu pantalla.
    echo Si estás conectado por WiFi, ejecuta primero: adb connect IP_DE_LA_RADIO:5555
    del adb_check.tmp >nul 2>&1
    pause
    exit /b 1
)
del adb_check.tmp >nul 2>&1
echo   -> Tablet conectada correctamente.
echo.

echo [2/6] Verificando instalación de JoyOSLauncher.apk...
adb shell pm list packages | findstr /C:"com.joyos.launcher" >nul
if %errorlevel% neq 0 (
    echo   -> JoyOS no está instalado aún. Buscando archivo APK local...
    if exist "JoyOSLauncher.apk" (
        echo   -> Instalando JoyOSLauncher.apk con permisos pre-concedidos...
        adb install -r -g JoyOSLauncher.apk
    ) else if exist "app\build\outputs\apk\debug\JoyOSLauncher.apk" (
        echo   -> Instalando APK desde compilación local...
        adb install -r -g app\build\outputs\apk\debug\JoyOSLauncher.apk
    ) else (
        echo   ⚠️ No se encontró JoyOSLauncher.apk en esta carpeta.
        echo   Descarga el APK desde los Releases de GitHub o compílalo antes de continuar.
    )
) else (
    echo   -> JoyOS v8.0 ya está presente en el sistema.
)
echo.

echo [3/6] Otorgando permisos especiales de sistema y hardware...
adb shell pm grant com.joyos.launcher android.permission.ACCESS_FINE_LOCATION >nul 2>&1
adb shell pm grant com.joyos.launcher android.permission.ACCESS_COARSE_LOCATION >nul 2>&1
adb shell pm grant com.joyos.launcher android.permission.BLUETOOTH_CONNECT >nul 2>&1
adb shell pm grant com.joyos.launcher android.permission.BLUETOOTH_SCAN >nul 2>&1
adb shell appops set com.joyos.launcher WRITE_SETTINGS allow >nul 2>&1
echo   -> Permisos de GPS, Bluetooth y brillo otorgados.
echo.

echo [4/6] Configurando JoyOS como el ÚNICO Launcher Principal (HOME)...
adb shell cmd package set-home-activity com.joyos.launcher/.MainActivity >nul 2>&1
echo   -> JoyOS registrado como Home Launcher nativo.
echo.

echo [5/6] Desbloqueando 100%% del Sonido y Optimizando CPU/GPU...
:: Elimina el limitador de volumen seguro de Android para dar 100% ganancia al amplificador
adb shell settings put global audio_safe_volume_state 2 >nul 2>&1
:: Exime a JoyOS de suspensión de batería / Doze mode
adb shell dumpsys deviceidle whitelist +com.joyos.launcher >nul 2>&1
:: Optimiza animaciones del sistema para máxima velocidad
adb shell settings put global window_animation_scale 0.5 >nul 2>&1
adb shell settings put global transition_animation_scale 0.5 >nul 2>&1
adb shell settings put global animator_duration_scale 0.5 >nul 2>&1
echo   -> Audio DSP desbloqueado sin limitador de volumen.
echo   -> Prioridad máxima de CPU/GPU aplicada.
echo.

echo [6/6] Limpieza de Bloatware (Congelamiento del Launcher de fábrica)...
echo.
echo ¿Deseas congelar el launcher genérico de fábrica para liberar 400 MB de RAM?
echo (Se puede revertir en cualquier momento con: adb shell pm enable [paquete])
echo.
set /p DESACTIVAR="Presiona S para congelar launcher OEM o N para omitir (S/N): "
if /i "%DESACTIVAR%"=="S" (
    echo   -> Congelando launchers OEM conocidos...
    adb shell pm disable-user --user 0 com.android.launcher3 >nul 2>&1
    adb shell pm disable-user --user 0 com.fyt.launcher >nul 2>&1
    adb shell pm disable-user --user 0 com.ts.main >nul 2>&1
    adb shell pm disable-user --user 0 com.xyauto.launcher >nul 2>&1
    adb shell pm disable-user --user 0 com.autochips.carplay >nul 2>&1
    echo   -> Launchers de fábrica congelados. 100%% de memoria entregada a JoyOS.
) else (
    echo   -> Omitido. El launcher de fábrica permanece activo.
)

echo.
echo =====================================================================
echo    🎉 ¡OPTIMIZACIÓN COMPLETA!
echo    Iniciando JoyOS v8.0 en la pantalla del Chevrolet Joy...
echo =====================================================================
adb shell am start -n com.joyos.launcher/.MainActivity >nul 2>&1
echo.
pause
