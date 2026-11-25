package oracle.simulacion.diccionario;

import java.math.BigDecimal;
import java.util.List;

/** Lo que devuelve el Registro v2 (diccionario de rutas de Simulación Titan). */
public final class DiccionarioRespuestas {

  private DiccionarioRespuestas() {}

  /**
   * Bloque de cluster del diccionario (CL1…CLn, en su orden).
   *
   * @param cerrado true si está cerrado en Configuración: el Registro no lo muestra hasta que se
   *     reabra.
   */
  public record Cluster(Long id, int orden, String nombre, boolean cerrado) {}

  /**
   * Una ruta con los totales de sus locales.
   *
   * @param clusterId null = está en Datos.
   * @param frecuencia LXV, MJS o DIARIA; null mientras no se asigne (siempre null en Datos).
   * @param grupo grupo dentro de su cluster; null mientras no se asigne (siempre null en Datos).
   * @param locales cuántos códigos tiene.
   * @param codigos sus códigos, en el orden en que se ingresaron (para buscar por local).
   */
  public record Ruta(
      Long id,
      String codigoRuta,
      String tipo,
      Long clusterId,
      String frecuencia,
      String grupo,
      BigDecimal bultos,
      BigDecimal unidades,
      BigDecimal lineas,
      int locales,
      List<String> codigos) {}

  /**
   * El diccionario completo para dibujar los bloques.
   *
   * @param truncado true si hay más rutas que el tope de la vista.
   */
  public record Diccionario(List<Cluster> clusters, List<Ruta> rutas, boolean truncado) {}

  /** Un local de la ruta; {@code filas} > 1 si su código se ingresó varias veces y se sumó. */
  public record Local(String cod, int filas, BigDecimal bultos, BigDecimal unidades, BigDecimal lineas) {}

  /** Una ruta con todos sus locales (ficha de detalle y edición). */
  public record Detalle(Ruta ruta, List<Local> locales) {}

  /** Las rutas que cambiaron (para redibujar solo esas) y cuántas se dejaron como estaban. */
  public record Cambio(List<Ruta> rutas, int omitidas) {}
}
