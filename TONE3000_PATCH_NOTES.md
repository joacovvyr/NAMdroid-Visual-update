# NAMDroid 0.3.3.5 — TONE3000

Parche para aplicar sobre 0.3.3.4 con AudioRouteController y modos Auto/Exclusive/Shared.
Copiar la carpeta app de este ZIP a la raíz del repositorio, combinando carpetas y reemplazando los cinco archivos incluidos. No borrar el resto del proyecto. Compilar de nuevo con GitHub Actions.

## Cambios

- TRY propaga el error real del motor; una carga rechazada ya no muestra éxito.
- Una prueba cargada activa NAM, quita el bypass y agrega el bloque AMP si fue eliminado.
- Conserva el rig activo al navegar por TONE3000 y guarda el modelo seleccionado en él.
- Repetir TRY sobre el mismo archivo vuelve a activar el amplificador.
- Descargar .nam, al lado de TRY, abre el selector de destino de Android. No cambia el modelo activo. Se puede cancelar.
- Las descargas se validan como JSON con arquitectura NAM antes de reemplazar el archivo local. Una descarga incompleta no reemplaza un modelo guardado.
- Para usar un archivo guardado sin conexión: AMP > LOCAL. Editar ganancia, EQ y efectos en la pedalera; guardar/exportar el rig. Esto no reentrena ni modifica los pesos de la captura NAM.
- Versión Android 0.3.3.5 / versionCode 10.

## Comprobaciones

Revisión estática de callbacks de carga, activación y navegación; comparación con 0.3.3.4: código nativo y controladores de dispositivos/rutas/modos sin cambios. ZIP comprobado.
No se ejecutó compilación Android ni prueba de audio físico en este entorno (sin SDK/dispositivo).

Probar en el teléfono:
1. Elegir un rig distinto al primero, poner AMP en bypass y abrir TONE3000. TRY debe activar el NAM elegido y conservar el mismo rig al volver.
2. Repetir TRY con AMP eliminado; debe añadirse antes del IR/salida.
3. Probar un modelo no compatible: debe verse el error, no una confirmación de éxito.
4. Descargar .nam, elegir destino, cargarlo desde LOCAL sin Internet. Cancelar el selector no debe afectar al audio.
5. Verificar entradas/salidas y Auto/Exclusive/Shared conservados.

Si sigue sin cambiar el sonido, enviar el mensaje exacto mostrado bajo el buscador y el nombre/enlace del modelo. También comprobar que se escucha la salida procesada y no únicamente el monitoreo directo de la interfaz.
