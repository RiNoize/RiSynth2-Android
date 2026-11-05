# RiSynth2 Android

La adaptacion Android esta en [`android/`](android/README.md). La version inicial 0.1.0 requiere **Android 13 o posterior y ARM64**; esta orientada a la Samsung Galaxy Tab A9, incluido Android 16.

**Descarga:** Actions → Build Android APK → una ejecucion terminada correctamente → Artifacts → RiSynth2-0.1.0-arm64. Descomprimir el ZIP e instalar el APK.

Si Actions esta deshabilitado por tratarse de un fork, habilitarlo en la pestana Actions y ejecutar Build Android APK con Run workflow.

Incluye audio AAudio, MIDI USB, sustain, pitch bend, carga .sy1/ZIP, bancos y programas, controles tactiles y diagnostico. No necesita ROM ni firmware. Salida local de la tablet por defecto; buffer solicitado 512. No reproduce audio en segundo plano en esta version.

El motor original `EngineCore/` se conserva sin modificaciones. La adaptacion debe probarse en hardware real: una compilacion correcta no garantiza consumo, latencia o estabilidad termica en la tablet. Ver las limitaciones y las instrucciones completas en android/README.md.
