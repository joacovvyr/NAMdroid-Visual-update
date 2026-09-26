cmake_minimum_required(VERSION 3.22.1)
project(namdroid_native)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Usamos float en todo el motor NAM (mucho mas rapido que double en telefonos).
# Activamos tambien la ruta A2 especializada: varios modelos WaveNet de
# TONE3000 tienen exactamente esta arquitectura y la implementacion generica
# de Eigen puede producir picos de CPU suficientes para provocar xruns en
# telefonos cuando el callback trabaja con buffers pequenos.
add_compile_definitions(
    NAM_SAMPLE_FLOAT
    NAM_ENABLE_A2_FAST=1
    NAM_A2_RING_MODE=1
)

set(NAM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/nam)

# Eigen (traido por scripts/setup_dependencies.sh) y nlohmann/json (vendorizado)
include_directories(
    ${NAM_DIR}/third_party/eigen
    ${NAM_DIR}/third_party/nlohmann
    ${NAM_DIR}
)

file(GLOB NAM_SOURCES
    ${NAM_DIR}/NAM/*.cpp
    ${NAM_DIR}/NAM/wavenet/*.cpp
)

# IMPORTANTE: las fuentes de NAM se compilan DIRECTO dentro de la libreria
# final (namdroid_native), no como una .a intermedia. NAM registra sus
# arquitecturas (WaveNet, LSTM, Container, SlimmableContainer, etc.) con
# objetos estaticos que se auto-registran en un registry global al cargar la
# libreria. Si esas fuentes viven en una libreria estatica separada, el
# linker descarta los .o "no referenciados" (container.cpp, slimmable.cpp...)
# porque nada llama sus funciones directamente - solo importa el efecto
# secundario del inicializador estatico - y el registro nunca corre. Eso es
# justo lo que causaba el error "No config parser registered for
# architecture: SlimmableContainer". Compilando todo junto en un solo target,
# el linker no puede podar nada.
add_library(nam_core OBJECT ${NAM_SOURCES})
target_include_directories(nam_core PUBLIC ${NAM_DIR})
# El motor se ejecuta dentro del callback de Oboe. Estas opciones reducen el
# costo de las operaciones matematicas del DSP y evitan que el compilador
# deje la ruta neural en una optimizacion conservadora de Debug.
target_compile_options(nam_core PRIVATE -O3 -ffast-math -fno-math-errno)

# Oboe via prefab (declarado en build.gradle.kts)
find_package(oboe REQUIRED CONFIG)

add_library(namdroid_native SHARED
    jni_bridge.cpp
    audio_engine.cpp
    $<TARGET_OBJECTS:nam_core>
)

target_include_directories(namdroid_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(namdroid_native
    oboe::oboe
    android
    log
)

target_compile_options(namdroid_native PRIVATE -O3 -ffast-math -fno-math-errno)
