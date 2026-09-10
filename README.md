# NAMDroid Pro 0.3.1

Procesador de guitarra Android de baja latencia, inspirado en el flujo de trabajo de las pedaleras profesionales de pantalla táctil. Usa Neural Amp Modeler, Oboe, C++ y Jetpack Compose.

## Experiencia principal

- Interfaz horizontal pensada para montar el teléfono en una pedalboard.
- Cadena de señal editable manteniendo pulsado y arrastrando los bloques.
- Biblioteca para agregar y eliminar módulos.
- Panel de edición con parámetros específicos para cada efecto.
- Rigs ilimitados guardados automáticamente.
- Cuatro escenas A–D por rig; el botón de disquete captura el estado actual.
- Biblioteca de rigs ordenada para utilizarla como setlist.
- Importación y exportación de todos los rigs en JSON, incluyendo modelos NAM e IR usados por los rigs.
- Tap tempo entre 30 y 300 BPM, sincronizado con el tiempo del delay.
- Medidores de entrada y salida con advertencia de clipping.
- Afinador cromático en tiempo real.
- Looper de 60 segundos con Record, Play, Overdub, Stop y Clear.
- Control MIDI USB/Bluetooth: Program Change selecciona rigs; CC20–23 escenas, CC24 afinador y CC25–27 looper.
- Protección de salida mediante limitador final.

## Bloques de audio

- Input Gain.
- Studio Compressor: threshold, ratio y makeup.
- Noise Gate: threshold y release.
- Green Drive: gain, tone y level.
- NAM Amplifier: modelo local o descargado desde TONE3000, input y output.
- IR Cabinet: WAV PCM/Float, level, low cut y high cut.
- Three Band EQ: low, mid y high.
- Dimension Chorus: rate, depth y mix.
- Digital Delay: time, feedback y mix.
- Plate Reverb: decay, tone y mix.
- Output Level.

Los efectos, el orden de la cadena, el looper, los medidores y el afinador se ejecutan en el motor nativo C++.

## TONE3000

- OAuth 2.0 con PKCE.
- Sesión cifrada y renovación automática mediante refresh token.
- Catálogo NAM A2 nativo con títulos, imágenes, creadores y variantes.
- Secciones Explore, Favorites, My Tones y Downloaded.
- Marcado y desmarcado de favoritos.
- `TRY` descarga el modelo, lo carga en caliente y arranca el audio.

La publishable key y el redirect URI están en `Tone3000Config.kt`. El redirect registrado en TONE3000 debe ser exactamente:

```text
namdroid://oauth/callback
```

No incluyas una secret key `t3k_cs_...` en la aplicación.

## Compilar con GitHub

El workflow `.github/workflows/build-apk.yml` prepara JDK 17, SDK 34, NDK `26.1.10909125`, CMake 3.22.1, Eigen y Gradle 8.7.

1. Subí el contenido del proyecto a la rama `main`.
2. Abrí **Actions → Build NAMDroid APK**.
3. Descargá el artefacto **NAMDroid-debug-apk**.

## Compilar localmente

```bash
bash scripts/setup_dependencies.sh
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

Conectá la interfaz USB antes de pulsar `START`; Android selecciona la entrada y salida activas al abrir Oboe.
