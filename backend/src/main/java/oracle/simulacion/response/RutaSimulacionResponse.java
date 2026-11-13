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
    Integer conteoCod,
    /** Grupo de la ruta dentro de su cluster (columna GRUPO del Excel). */
    String grupo,
    /** DIARIA, LXV o MJS: en qué balanceo aparece (las diarias, en los tres). */
    String frecuencia,
    /** Ruta diaria ya balanceada (paso Diarias): el automático de los días no la vuelve a mover. */
    boolean diariaFija,
    /** Días tal como vienen en el Excel («L-X», «V»); null en rutas anteriores a las frecuencias. */
    String dias) {

  public RutaSimulacionResponse(
      Long id,
      Long chuteId,
      String chute,
      Long chuteOriginalId,
      String chuteOriginal,
      Long clusterId,
      String cluster,
      Long clusterOriginalId,
      String clusterOriginal,
      boolean clusterEditable,
      String codigoRuta,
      String tipo,
      BigDecimal totalBultos,
      BigDecimal totalUnidades,
      BigDecimal totalLineas,
      Integer conteoCod,
      String grupo,
      String frecuencia,
      boolean diariaFija) {
    this(
        id, chuteId, chute, chuteOriginalId, chuteOriginal, clusterId, cluster, clusterOriginalId,
        clusterOriginal, clusterEditable, codigoRuta, tipo, totalBultos, totalUnidades, totalLineas,
        conteoCod, grupo, frecuencia, diariaFija, null);
  }


  public RutaSimulacionResponse(
      Long id,
      Long chuteId,
      String chute,
      Long chuteOriginalId,
      String chuteOriginal,
      Long clusterId,
      String cluster,
      Long clusterOriginalId,
      String clusterOriginal,
      boolean clusterEditable,
      String codigoRuta,
      String tipo,
      BigDecimal totalBultos,
      BigDecimal totalUnidades,
      BigDecimal totalLineas,
      Integer conteoCod,
      String grupo) {
    this(
        id, chuteId, chute, chuteOriginalId, chuteOriginal, clusterId, cluster, clusterOriginalId,
        clusterOriginal, clusterEditable, codigoRuta, tipo, totalBultos, totalUnidades, totalLineas,
        conteoCod, grupo, "DIARIA", false);
  }
}
