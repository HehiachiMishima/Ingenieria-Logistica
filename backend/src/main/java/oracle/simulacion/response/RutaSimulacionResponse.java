package oracle.simulacion.response;

import java.math.BigDecimal;

public record RutaSimulacionResponse(
    Long id,
    Long chuteId,
    String chute,
    /** Chute que tenía la ruta al cargar el Excel. */
    Long chuteOriginalId,
    String chuteOriginal,
    Long clusterId,
    String cluster,
    String codigoRuta,
    String tipo,
    BigDecimal totalBultos,
    BigDecimal totalUnidades,
    BigDecimal totalLineas,
    Integer conteoCod) {}
