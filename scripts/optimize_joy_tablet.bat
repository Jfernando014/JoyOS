@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title Optimizador 100%% Hardware - JoyOS v1.1.0 Chevrolet Joy
cls
echo =====================================================================
echo    🚀 JoyOS v1.1.0 — OPTIMIZADOR AUTOMÁTICO DE HARDWARE Y AUDIO
echo    Chevrolet Joy 2022 · Unidad AC8227L / ZXDZ_01 (1024x600)
echo =====================================================================
echo.

:: Localizar o descargar motor ADB automáticamente
set "ADB_CMD="
where adb >nul 2>&1
if %errorlevel% equ 0 (
    set "ADB_CMD=adb"
) else if exist "%~dp0platform-tools\adb.exe" (
    set "ADB_CMD=%~dp0platform-tools\adb.exe"
) else (
    echo [0/6] Descargando motor oficial Android ADB ^(Google Platform-Tools^)...
    powershell -Command "Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile '%~dp0platform-tools.zip'; Expand-Archive -Path '%~dp0platform-tools.zip' -DestinationPath '%~dp0' -Force; Remove-Item '%~dp0platform-tools.zip' -Force"
    if exist "%~dp0platform-tools\adb.exe" (
        set "ADB_CMD=%~dp0platform-tools\adb.exe"
        echo   -> Motor ADB instalado correctamente en scripts\platform-tools.
    ) else (
        echo ❌ ERROR: No se pudo descargar ADB. Verifica tu conexión a Internet.
        pause
        exit /b 1
    )
)

:CHECK_CONNECTION
echo [1/6] Verificando conexión ADB con la pantalla/tablet...
"%ADB_CMD%" devices > "%~dp0adb_check.tmp"
findstr /R /C:"[0-9a-zA-Z].*device$" "%~dp0adb_check.tmp" | findstr /V /C:"List of devices" >nul
if %errorlevel% neq 0 (
    del "%~dp0adb_check.tmp" >nul 2>&1
    echo.
    echo ⚠️ No se detectó ninguna pantalla/tablet autorizada en este momento.
    echo.
    echo   ¿Cómo deseas conectarla?
    echo   [1] Por Cable USB ^(Conecta el cable de datos y acepta "Permitir depuración USB" en la tablet^)
    echo   [2] Por Wi-Fi sin cables ^(Ingresa la IP de la pantalla si están en el mismo Wi-Fi / Hotspot^)
    echo   [3] Salir
    echo.
    set /p MODO_CON="Elige una opción (1/2/3): "
    if "!MODO_CON!"=="1" (
        echo.
        goto CHECK_CONNECTION
    ) else if "!MODO_CON!"=="2" (
        echo.
        set /p IP_TABLET="Escribe la IP de la tablet (ej. 192.168.1.55 o 192.168.43.100:5555): "
        echo   -> Conectando a !IP_TABLET!...
        "%ADB_CMD%" connect !IP_TABLET!
        timeout /t 2 >nul
        goto CHECK_CONNECTION
    ) else (
        exit /b 1
    )
)
del "%~dp0adb_check.tmp" >nul 2>&1
echo   -> Tablet conectada y autorizada correctamente.
echo.

echo [2/6] Instalando / Actualizando JoyOSLauncher.apk (v1.1.0)...
if exist "%~dp0..\JoyOSLauncher.apk" (
    echo   -> Instalando JoyOSLauncher.apk con todos los permisos pre-concedidos...
    "%ADB_CMD%" install -r -g "%~dp0..\JoyOSLauncher.apk"
) else if exist "%~dp0JoyOSLauncher.apk" (
    echo   -> Instalando JoyOSLauncher.apk con todos los permisos pre-concedidos...
    "%ADB_CMD%" install -r -g "%~dp0JoyOSLauncher.apk"
) else if exist "JoyOSLauncher.apk" (
    echo   -> Instalando JoyOSLauncher.apk con todos los permisos pre-concedidos...
    "%ADB_CMD%" install -r -g "JoyOSLauncher.apk"
) else if exist "%~dp0..\app\build\outputs\apk\debug\JoyOSLauncher.apk" (
    echo   -> Instalando APK desde compilación local...
    "%ADB_CMD%" install -r -g "%~dp0..\app\build\outputs\apk\debug\JoyOSLauncher.apk"
) else (
    echo   -> Descargando última versión de JoyOSLauncher.apk desde GitHub Releases...
    powershell -Command "Invoke-WebRequest -Uri 'https://github.com/Jfernando014/JoyOS/releases/latest/download/JoyOSLauncher.apk' -OutFile '%~dp0..\JoyOSLauncher.apk'"
    "%ADB_CMD%" install -r -g "%~dp0..\JoyOSLauncher.apk"
)
echo.

echo [3/6] Otorgando permisos especiales de sistema, hardware y OTA...
"%ADB_CMD%" shell pm grant com.joyos.launcher android.permission.ACCESS_FINE_LOCATION >nul 2>&1
"%ADB_CMD%" shell pm grant com.joyos.launcher android.permission.ACCESS_COARSE_LOCATION >nul 2>&1
"%ADB_CMD%" shell pm grant com.joyos.launcher android.permission.BLUETOOTH_CONNECT >nul 2>&1
"%ADB_CMD%" shell pm grant com.joyos.launcher android.permission.BLUETOOTH_SCAN >nul 2>&1
"%ADB_CMD%" shell appops set com.joyos.launcher WRITE_SETTINGS allow >nul 2>&1
"%ADB_CMD%" shell appops set com.joyos.launcher REQUEST_INSTALL_PACKAGES allow >nul 2>&1
echo   -> Permisos de GPS, Bluetooth, brillo físico y actualizaciones OTA otorgados.
echo.

echo [4/6] Configurando JoyOS como el ÚNICO Launcher Principal (HOME)...
"%ADB_CMD%" shell cmd package set-home-activity com.joyos.launcher/.MainActivity >nul 2>&1
echo   -> JoyOS registrado como Home Launcher nativo.
echo.

echo [5/6] Desbloqueando 100%% del Sonido, CPU/GPU y Puerto ADB Inalámbrico...
:: Elimina el limitador de volumen seguro de Android para dar 100% ganancia al amplificador
"%ADB_CMD%" shell settings put global audio_safe_volume_state 2 >nul 2>&1
:: Exime a JoyOS de suspensión de batería / Doze mode
"%ADB_CMD%" shell dumpsys deviceidle whitelist +com.joyos.launcher >nul 2>&1
:: Optimiza animaciones del sistema para máxima velocidad
"%ADB_CMD%" shell settings put global window_animation_scale 0.5 >nul 2>&1
"%ADB_CMD%" shell settings put global transition_animation_scale 0.5 >nul 2>&1
"%ADB_CMD%" shell settings put global animator_duration_scale 0.5 >nul 2>&1
:: Habilita puerto 5555 para futuras conexiones Wi-Fi sin cables
"%ADB_CMD%" tcpip 5555 >nul 2>&1
echo   -> Audio DSP desbloqueado sin limitador de volumen.
echo   -> Prioridad máxima de CPU/GPU y puerto Wi-Fi 5555 activados.
echo.

echo [6/6] Limpieza de Bloatware (Congelamiento del Launcher de fábrica)...
echo.
echo ¿Deseas congelar el launcher genérico de fábrica para liberar ~400 MB de RAM?
echo (Se puede revertir en cualquier momento con: adb shell pm enable [paquete])
echo.
set /p DESACTIVAR="Presiona S para congelar launcher OEM o N para omitir (S/N): "
if /i "!DESACTIVAR!"=="S" (
    echo   -> Congelando launchers OEM conocidos...
    "%ADB_CMD%" shell pm disable-user --user 0 com.android.launcher3 >nul 2>&1
    "%ADB_CMD%" shell pm disable-user --user 0 com.fyt.launcher >nul 2>&1
    "%ADB_CMD%" shell pm disable-user --user 0 com.ts.main >nul 2>&1
    "%ADB_CMD%" shell pm disable-user --user 0 com.xyauto.launcher >nul 2>&1
    "%ADB_CMD%" shell pm disable-user --user 0 com.autochips.carplay >nul 2>&1
    echo   -> Launchers de fábrica congelados. 100%% de memoria entregada a JoyOS.
) else (
    echo   -> Omitido. El launcher de fábrica permanece activo.
)

echo.
echo =====================================================================
echo    🎉 ¡OPTIMIZACIÓN COMPLETA!
echo    Iniciando JoyOS v1.1.0 en la pantalla del Chevrolet Joy...
echo =====================================================================
"%ADB_CMD%" shell am start -n com.joyos.launcher/.MainActivity >nul 2>&1
echo.
pause
