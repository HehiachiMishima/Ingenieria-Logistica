package oracle.simulacion.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Distribución de rutas por cluster y chute: lo que exportan el Excel y el PDF. Un bloque por
 * cluster (sin repetir por día) con todos los chutes del plano en columnas; cada celda lleva los
 * números de ruta con su frecuencia y, abajo, los bultos de cada día.
 *
 * @param registro nombre del registro
 * @param generado cuándo se armó
 * @param chutes chutes del plano en orden natural, los bloqueados incluidos
 * @param clusters clusters con rutas, en el orden de las olas
 * @param totales indicadores del registro
 */
public record DistribucionResponse(
    String registro,
    Instant generado,
    List<Chute> chutes,
    List<Cluster> clusters,
    Totales totales) {

  /**
   * Chute del plano; los bloqueados no tienen id (no reciben rutas). {@code manual}: de uso manual
   * (19, 20, 21), con su franja de peligro; sus rutas se muestran como en cualquier chute.
   */
  public record Chute(Long id, String nombre, boolean buff, boolean bloqueado, boolean manual) {
    public Chute(Long id, String nombre, boolean buff, boolean bloqueado) {
      this(id, nombre, buff, bloqueado, false);
    }
  }

  /**
   * Una ruta en su celda, como la ficha de la pantalla: código, frecuencia (DIARIA, LXV o MJS), su
   * grupo con el color de la cuadrícula (#rrggbb; null sin grupo), sus bultos y su tipo (B2C o
   * B2B: la misma ruta puede venir con los dos).
   */
  public record Ruta(
      String codigo, String frecuencia, String grupo, String color, BigDecimal bultos, String tipo) {}

  /**
   * Un chute con rutas del cluster: las rutas (diarias, luego L-X-V y M-J-S) y los bultos de cada
   * día, redondeados al entero (diarias + rutas de ese día).
   */
  public record Celda(Long chuteId, List<Ruta> rutas, long bultosLxv, long bultosMjs,
      Integer operariosLxv, Integer operariosMjs) {}

  /** Un cluster: cuántas rutas tiene de cada frecuencia, sus bultos por día y sus celdas. */
  public record Cluster(
      Long id,
      String nombre,
      long rutas,
      long diarias,
      long lxv,
      long mjs,
      long bultosLxv,
      long bultosMjs,
      List<Celda> celdas) {}

  /** Indicadores: clusters con rutas, rutas, chutes con alguna ruta y bultos de cada día. */
  public record Totales(int clusters, long rutas, int chutesEnUso, long bultosLxv, long bultosMjs) {}
}
