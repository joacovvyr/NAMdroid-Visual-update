# NAMDroid STAGE 0.4.1

## Nueva interfaz de equipos interactivos

- Carcasas originales y optimizadas de pedales, amplificador y gabinete.
- Perillas nativas Compose: responden al arrastre vertical, muestran su valor y modifican el parámetro real.
- LED vinculado al estado activo/bypass y footswitch independiente de la imagen de la carcasa.
- Barra superior compacta con menú desplegable para TONE3000, audio, rigs, efectos, afinador y looper.
- Conserva el motor de audio, rutas Auto/Exclusive/Shared, rigs, escenas, sesión y arreglos de TONE3000 anteriores.

Actualización de interfaz y flujo de uso sobre 0.3.3.5. Incluye el arreglo de TONE3000 de 0.3.3.5 y puede aplicarse sobre 0.3.3.4 con Shared/Exclusive.

## Instalación

1. Descomprimir el ZIP y combinar su carpeta `app` con `app` en la raíz del repositorio. Reemplazar los archivos coincidentes. Conservar todos los demás archivos.
2. Hacer commit en la rama usada por GitHub Actions y esperar la compilación.
3. Descargar el APK generado. Instalar sobre la app anterior si la firma coincide, para conservar rigs y sesión. Este ZIP es código fuente; no es un APK.

Versión: 0.4.1, versionCode 12. No cambia los identificadores de la aplicación, las preferencias existentes ni los controladores nativos de audio.

## Experiencia STAGE

- Cadena numerada y adaptable de 2 a 6 columnas según el ancho disponible; las filas se desplazan si no caben. La numeración define el orden serial del audio.
- Dibujos vectoriales propios de pedales, amplificadores y pantallas, colores por familia, indicadores activos/bypass y medidores IN/OUT.
- Mantener y arrastrar un bloque; soltarlo sobre otro para cambiar el orden. Input/Output permanecen en sus posiciones. El editor también ofrece Mover antes/después.
- Tocar un bloque abre el editor en el área central, sin una columna lateral que comprima la cadena.
- Perillas por arrastre vertical, sliders, entrada numérica exacta con validación de rango y botón para restablecer cada parámetro.
- Modo LIVE: tocar efectos cambia bypass; mantener un efecto abre su editor. Las escenas A–D permanecen accesibles.
- TAP visible. Herramientas abre guardar escena, agregar efecto, afinador, looper, rigs y audio. Guardar escena pide confirmación antes de reemplazar el snapshot.
- TONE3000 y entrada/salida accesibles desde la barra superior. TRY y descarga de .nam conservados.
- Biblioteca de rigs de pantalla completa, búsqueda, orden de setlist, importación y exportación; confirmación al borrar.
- Afinador y looper en ventanas amplias; borrar un loop requiere confirmación.
- La pantalla permanece encendida mientras se usa la aplicación.

## Estado y compatibilidad

Se conserva el último rig seleccionado y se restauran sus NAM/IR al iniciar. Si un archivo falta o falla al cargar, ese bloque se desactiva para evitar reproducir una captura anterior por error. Las cargas locales de NAM activan el amplificador igual que TRY.

Los respaldos vacíos y rigs con identificadores/tipos repetidos se rechazan. Los valores fuera de rango se limitan al rango del efecto. La importación muestra errores y confirmación de éxito.

Shared/Exclusive/Auto, selección/persistencia de entrada y salida, altavoz forzado y reinicio de rutas conservan su implementación anterior. El motor sigue siendo una cadena serial con una instancia por tipo de efecto. El diseño se inspira en pedaleras táctiles profesionales; no emula el firmware HeadRush.

## Validación realizada

- Parser Kotlin 1.9.22: todos los archivos Kotlin, incluidas las previews, sin errores sintácticos.
- Comparación del motor C++, JNI, NamEngine y controladores de audio con 0.3.3.5: sin cambios.
- Integridad del ZIP y archivos incluidos verificados.
- Intento de compilación con Gradle 8.7 y SDK Android 34: bloqueado al resolver `com.android.application:8.5.2` en los repositorios desde este entorno, antes de compilar la app. No se presenta como una compilación exitosa.
- No se ejecutó en un teléfono ni se escuchó audio real. No se verificó todavía la apariencia renderizada. Las previews están en `app/src/debug/.../StagePreview.kt`, con tamaños 780×360, 640×320 y 1024×600 dp para Android Studio.

## Prueba en el teléfono

1. Revisar que el rig y las rutas guardadas sigan seleccionados. Iniciar audio.
2. Arrastrar Drive antes/después de NAM y comprobar orden audible.
3. Abrir un efecto, ajustar perilla, slider y valor exacto; volver sin perder cambios.
4. En LIVE, alternar bypass y escenas. Guardar una escena y recuperarla.
5. Seleccionar otro rig, cerrar y abrir la app; comprobar restauración de modelos.
6. TRY en TONE3000 con AMP en bypass, luego descargar .nam y cargarlo desde LOCAL sin conexión.
7. Grabar/reproducir un loop y revisar afinador, ruteo y Shared/Exclusive con la interfaz utilizada.

Para evaluar estabilidad de uso en vivo todavía hace falta medir latencia, cortes y temperatura con cada combinación de teléfono e interfaz.
