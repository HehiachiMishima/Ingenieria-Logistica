package oracle.picking.response;

import java.time.Instant;

/**
 * Estado del archivo del panel: el que hay en la carpeta (con la fecha que
 * tenía en el servidor del WMS) y el resultado de la última revisión del SFTP.
 */
public record EstadoPickingResponse(
        /** ACTUALIZADO, SIN_CAMBIOS, SIN_ARCHIVO, ERROR, PENDIENTE o NO_CONFIGURADO. */
        String sincronizacion,
        String mensaje,
        Instant revisado,
        boolean disponible,
        String archivo,
        Long bytes,
        Instant modificado,
        String huella) {}
