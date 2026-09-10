# Subir NAMDroid STAGE 0.4.1 a GitHub

Este ZIP contiene el proyecto Android completo. No es un APK.

## Repositorio nuevo

1. Descomprimí el ZIP.
2. Abrí la carpeta `NAMDroid-main`.
3. Subí **el contenido de esa carpeta** a la raíz del repositorio. En GitHub deben verse directamente `app`, `.github`, `build.gradle.kts`, `settings.gradle.kts` y los demás archivos.
4. No subas una carpeta adicional que contenga todo el proyecto, porque el workflow no encontrará Gradle.
5. En GitHub, abrí `Actions` y ejecutá `Compilar APK de debug`.
6. Cuando termine, descargá el artefacto APK desde la ejecución.

## Actualizar un repositorio existente

Copiá el contenido de `NAMDroid-main` sobre la raíz del repositorio y aceptá reemplazar los archivos coincidentes. Después hacé commit y push.

## Versión

- `versionName`: 0.4.1
- `versionCode`: 12
- Conserva el mismo `applicationId`, por lo que puede instalarse como actualización si la firma coincide.

## Verificación local

El proyecto requiere Android SDK 34, Java 17, NDK 26.1.10909125 y CMake 3.22.1. La compilación final debe realizarse en GitHub Actions o Android Studio con acceso a los repositorios de Google y Maven.
