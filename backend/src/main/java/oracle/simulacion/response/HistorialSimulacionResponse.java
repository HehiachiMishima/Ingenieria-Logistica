package oracle.simulacion.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un cambio del historial con los datos para leerlo sin consultar nada más:
 * la ruta (cluster, tipo, bultos), los chutes y cuántos operarios quedaron.
 */
public record HistorialSimulacionResponse(
    Long id,
    /** Lanzamiento que hizo el cambio; null = movimiento manual o de una versión anterior. */
    Long lanzamientoId,
    String tipoAccion,
    /** BALANCEO, SEPARACION (CL1 y CL2) o MANUAL. */
    String motivo,
    Long rutaId,
    String ruta,
    String cluster,
    String tipo,
    BigDecimal bultos,
    String chuteOrigen,
    String chuteDestino,
    String chuteModificado,
    /**
     * MOVER_RUTA: operarios de origen y destino; ASIGNAR_OPERARIOS: los del
     * chute en la ola antes y después.
     */
    Integer operariosOrigen,
    Integer operariosDestino,
    Instant creadoEn,
    Long creadoPor) {}
