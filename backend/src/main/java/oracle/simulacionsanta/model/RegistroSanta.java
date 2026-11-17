package oracle.simulacionsanta.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import oracle.common.model.Auditable;

/**
 * Una carga del Excel de despacho de Santa Anita. Las filas no cambian después de cargarse, así
 * que sus totales se guardan aquí y el listado no recorre las filas. También guarda la
 * configuración del registro salvo las ubicaciones (tabla simulacion_santa.ubicaciones).
 */
@Entity
@Table(schema = "simulacion_santa", name = "registros")
@Getter
@Setter
public class RegistroSanta extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "nombre", length = 150, nullable = false)
  private String nombre;

  @Column(name = "nombre_archivo", length = 255, nullable = false)
  private String nombreArchivo;

  /** 0 a 3: envíos ya recibidos. */
  @Column(name = "envio_actual", nullable = false)
  private int envioActual;

  /**
   * Columna calculada por PostgreSQL a partir de envio_actual (solo para filtrar el listado); en
   * memoria se usa {@link EstadoRegistro#de(int)}.
   */
  @Column(name = "estado", length = 10, insertable = false, updatable = false)
  private String estado;

  @Column(name = "paletas", nullable = false)
  private int paletas;

  @Column(name = "rutas", nullable = false)
  private int rutas;

  @Column(name = "filas_omitidas", nullable = false)
  private int filasOmitidas;

  @Column(name = "bultos_envio1", nullable = false)
  private int bultosEnvio1;

  @Column(name = "bultos_envio2", nullable = false)
  private int bultosEnvio2;

  @Column(name = "bultos_envio3", nullable = false)
  private int bultosEnvio3;

  @Column(name = "paletas_cluster1", nullable = false)
  private int paletasCluster1;

  @Column(name = "paletas_cluster2", nullable = false)
  private int paletasCluster2;

  @Column(name = "paletas_cluster3", nullable = false)
  private int paletasCluster3;

  @Column(name = "operarios", nullable = false)
  private int operarios;

  @Column(name = "bultos_hora", nullable = false)
  private int bultosHora;

  /** Nombre de un {@link OrdenLlenado}. */
  @Column(name = "orden_cluster1", length = 16, nullable = false)
  private String ordenCluster1;

  @Column(name = "orden_cluster2", length = 16, nullable = false)
  private String ordenCluster2;

  @Column(name = "orden_cluster3", length = 16, nullable = false)
  private String ordenCluster3;

  @Column(name = "configurado_por", nullable = false)
  private Long configuradoPor;

  @Column(name = "configurado_en", nullable = false)
  private Instant configuradoEn;
}
