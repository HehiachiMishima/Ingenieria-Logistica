package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Los locales de una ruta tal como vinieron en el Excel (el detalle que se ve al hacer clic en
 * ella).
 *
 * @param conDetalle false si el archivo se subió antes de guardar los locales: hay que volver a
 *     subirlo para verlos.
 * @param truncado true si la ruta tiene más locales que el tope de la consulta.
 */
public record LocalesRutaResponse(Long rutaId, boolean conDetalle, boolean truncado, List<Local> locales) {

  /**
   * @param cod código del local; null si la fila del Excel no traía COD.
   * @param filas filas del Excel con ese COD (más de una: se suman).
   */
  public record Local(String cod, int filas, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas) {}
}
