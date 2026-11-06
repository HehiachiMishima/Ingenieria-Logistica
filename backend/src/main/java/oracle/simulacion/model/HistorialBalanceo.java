package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Cambio sobre el registro: ruta movida (paso 1 del balanceo, separación de
 * CL1 y CL2 o a mano) u operarios asignados a un chute en una ola (paso 2).
 * Los cambios del balanceo apuntan a su lanzamiento. La tabla conserva
 * también AÑADIR_OPERARIO y MOVER_OPERARIO de registros anteriores.
 */
@Entity
@Table(schema = "simulacion", name = "historial_balanceo")
@Getter
@Setter
public class HistorialBalanceo {

  public static final String ACCION_MOVER_RUTA = "MOVER_RUTA";
  /** Paso 2: operarios del chute antes → después (operarios_origen → operarios_destino). */
  public static final String ACCION_ASIGNAR_OPERARIOS = "ASIGNAR_OPERARIOS";
  /**
   * Ruta pasada a otro cluster a mano (cluster_origen_id → cluster_destino_id).
   * chute_origen_id guarda el chute que tenía en ese momento: restablecer la
   * devuelve ahí.
   */
  public static final String ACCION_CAMBIAR_CLUSTER = "CAMBIAR_CLUSTER";

  public static final String MOTIVO_BALANCEO = "BALANCEO";
  public static final String MOTIVO_SEPARACION = "SEPARACION";
  public static final String MOTIVO_MANUAL = "MANUAL";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "archivo_id", nullable = false)
  private Long archivoId;

  /** Lanzamiento que hizo el cambio; null = movimiento manual. */
  @Column(name = "lanzamiento_id")
  private Long lanzamientoId;

  @Column(name = "tipo_accion", length = 30, nullable = false)
  private String tipoAccion;

  @Column(name = "ruta_id")
  private Long rutaId;

  @Column(name = "chute_origen_id")
  private Long chuteOrigenId;

  @Column(name = "chute_destino_id")
  private Long chuteDestinoId;

  @Column(name = "chute_modificado_id")
  private Long chuteModificadoId;

  /** CAMBIAR_CLUSTER: cluster que tenía la ruta y al que pasó. */
  @Column(name = "cluster_origen_id")
  private Long clusterOrigenId;

  @Column(name = "cluster_destino_id")
  private Long clusterDestinoId;

  /**
   * Cambios a mano (MOVER_RUTA y CAMBIAR_CLUSTER sin lanzamiento): cluster que
   * estaba en curso al hacerlos. Restablecer ese cluster, o reabrir uno
   * anterior, deshace exactamente ese trabajo. Los del balanceo se atribuyen
   * por su lanzamiento. Null en filas anteriores a esta columna.
   */
  @Column(name = "cluster_trabajo_id")
  private Long clusterTrabajoId;

  @Column(name = "motivo", length = 20, nullable = false)
  private String motivo = MOTIVO_BALANCEO;

  /** Operarios del chute de origen después de la acción. */
  @Column(name = "operarios_origen")
  private Integer operariosOrigen;

  /** Operarios del chute de destino (o del modificado) después de la acción. */
  @Column(name = "operarios_destino")
  private Integer operariosDestino;

  @Column(name = "creado_por", nullable = false)
  private Long creadoPor;

  @Column(name = "creado_en", nullable = false)
  private Instant creadoEn;
}
