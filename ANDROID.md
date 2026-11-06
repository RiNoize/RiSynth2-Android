# RiSynth2 Android 0.2.0

La adaptación Android está en [`android/`](android/README.md). Requiere **Android 13 o posterior y ARM64**; está orientada a la Samsung Galaxy Tab A9, incluido Android 16.

**Descarga:** Actions → Build Android APK → una ejecución terminada correctamente → Artifacts → RiSynth2-0.2.0-arm64. Descomprimir el ZIP e instalar el APK sobre la versión anterior, sin desinstalar ni borrar sus datos.

Esta versión agrega un **monitor fijo de CPU app, render, overruns y XRuns**, con informe copiable y reinicio de contadores. **Importar → Carpeta completa** permite elegir la carpeta madre de todos los bancos, recorrer sus subcarpetas y conservar los nombres y números de programa. Incluye progreso, cancelación y buscador de bancos. La biblioteca carga el contenido de cada patch al seleccionarlo.

Conserva audio AAudio, MIDI USB, sustain, pitch bend, carga .sy1/ZIP, bancos y programas y controles táctiles. No necesita ROM ni firmware. Salida local de la tablet por defecto. Las instalaciones nuevas solicitan 1024 frames; una configuración guardada se respeta. No reproduce audio en segundo plano en esta versión.

El motor original `EngineCore/` se conserva sin modificaciones. El usuario reportó cortes con 512 y mejor funcionamiento con 1024 en 0.1.0: este monitor ayudará a investigarlos, no implica que estén corregidos. La compilación y las pruebas automáticas no sustituyen las pruebas en la tablet. Ver detalles y límites en android/README.md.
