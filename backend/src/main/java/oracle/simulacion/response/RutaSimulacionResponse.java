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
    /** Cluster que tenía la ruta al cargar el Excel (distinto si se cambió a mano). */
    Long clusterOriginalId,
    String clusterOriginal,
    /** El cluster de la ruta se puede cambiar: el suyo y algún otro siguen sin balancear. */
    boolean clusterEditable,
    String codigoRuta,
    String tipo,
    BigDecimal totalBultos,
    BigDecimal totalUnidades,
    BigDecimal totalLineas,
    Integer conteoCod) {}
