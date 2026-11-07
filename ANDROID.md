# RiSynth2 Android 0.3.1

La adaptación Android está en [`android/`](android/README.md). Requiere **Android 13 o posterior y ARM64**; está orientada a la Samsung Galaxy Tab A9, incluido Android 16.

**Descarga:** Actions → Build Android APK → una ejecución terminada correctamente → Artifacts → RiSynth2-0.3.1-preview-arm64. Descomprimir el ZIP. Si tenés la 0.1.0, instalar **RiSynth2-0.3.1-preview-arm64.apk**: aparecerá como **RiSynth2 Preview**, sin reemplazar ni borrar la anterior. Los bancos se importan nuevamente desde sus carpetas; los datos privados no se comparten entre ambas aplicaciones. La firma de 0.1.0 no quedó preservada por un error de caché; la APK normal no puede actualizarla directamente. No desinstales la anterior para probar esta versión.

Esta versión agrega un **monitor fijo de CPU app, render, overruns y XRuns**, con informe copiable y reinicio de contadores. **Importar → Carpeta completa** permite elegir la carpeta madre de todos los bancos, recorrer sus subcarpetas y conservar los nombres y números de programa. Incluye progreso, cancelación y buscador de bancos. La biblioteca carga el contenido de cada patch al seleccionarlo.

Conserva audio AAudio, MIDI USB, sustain, pitch bend, carga .sy1/ZIP, bancos y programas y controles táctiles. No necesita ROM ni firmware. Salida local de la tablet por defecto. Las instalaciones nuevas solicitan 1024 frames; una configuración guardada se respeta. No reproduce audio en segundo plano en esta versión.

El motor conserva sus fórmulas, tablas y polifonía; 0.3.1 evita cálculos repetidos y comparte la modulación global de afinación entre voces. Ver `android/OPTIMIZATION.md` y las comparaciones exactas de audio. El usuario reportó cortes con 512 y mejor funcionamiento con 1024 en 0.1.0: este monitor ayudará a investigarlos, no implica que estén corregidos. La compilación y las pruebas automáticas no sustituyen las pruebas en la tablet. Ver detalles y límites en android/README.md.

## Favoritos y actualización 0.3.1

**☆ Guardar favorito** copia el sonido actual con tus ajustes a la colección privada **★ Favoritos**. No modifica el banco original ni cambia el sonido seleccionado; evita duplicar una copia idéntica. Al superar 128 sonidos crea otro banco de favoritos. Los favoritos se conservan al cerrar y abrir la aplicación. Exportá los importantes antes de desinstalar o borrar datos.

Para actualizar **Preview 0.2.0**, instalá **Preview 0.3.1 encima**, sin desinstalar ni reimportar los bancos. Conserva el mismo paquete y certificado de firma fijado en el repositorio. La mejora de CPU depende del patch y debe medirse en la tablet; no se promete corregir todos los cortes.

## Optimización adicional 0.3.1

La variante **Preview** y la normal tienen el mismo motor; Preview es la actualización que corresponde a Preview 0.2/0.3. No desinstalar ni cambiar a la normal para intentar mejorar el consumo. Los artefactos ahora se descargan separados, con un solo APK por ZIP.

Se precalculan los coeficientes de interpolación de los osciladores, se comparten más modulaciones globales, se elige el cálculo de forma de onda fuera del bucle por muestra y se evitan divisiones innecesarias. No se reduce polifonía, calidad, efectos ni frecuencia de muestreo. El beneficio depende del patch; las pruebas de servidor ARM64 no son medidas en la Tab A9.

El detalle del monitor muestra **Voces/LFO** y **Efectos/salida** por separado. **Copiar informe + patch** agrega los parámetros del sonido actual al informe; no envía archivos automáticamente. Ver android/OPTIMIZATION031.md.
