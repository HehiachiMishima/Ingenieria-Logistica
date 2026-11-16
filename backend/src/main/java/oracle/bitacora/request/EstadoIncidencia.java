package oracle.bitacora.request;

/**
 * Estado de una incidencia. No se guarda: se deduce del cierre
 * ({@code cerrado_en} nulo = abierta), así nunca contradice al fin registrado.
 */
public enum EstadoIncidencia {
    ABIERTA,
    CERRADA;

    public static EstadoIncidencia de(java.time.Instant cerradoEn) {
        return cerradoEn == null ? ABIERTA : CERRADA;
    }
}
