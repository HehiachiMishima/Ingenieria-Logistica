package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * Ruta del escenario. En DATA (sin cluster) no se balancea y puede no tener tipo, frecuencia ni
 * grupo; en un cluster está completa. Las escrituras de DATA van por JDBC ({@code RutasDataRepository}).
 */
@Entity
@Table(schema = "simulacion", name = "rutas_proyeccion")
@Getter
@Setter
public class RutaProyeccion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "archivo_id", nullable = false)
  private Long archivoId;

  @Column(name = "chute_id", nullable = false)
  private Long chuteId;

  /** Chute al pasar de DATA a un cluster (no lo altera el balanceo). En DATA, igual a chuteId. */
  @Column(name = "chute_original_id", nullable = false)
  private Long chuteOriginalId;

  /**
   * Columna generada (STORED) en PostgreSQL (chute_id <> chute_original_id) con índice parcial: las
   * consultas de rutas movidas no recorren todo el registro. Solo lectura.
   */
  @Column(name = "movida", insertable = false, updatable = false)
  private boolean movida;

  /** Cluster de hoy; null = la ruta está en DATA. */
  @Column(name = "cluster_id")
  private Long clusterId;

  /** Cluster al pasar de DATA: el balanceo no lo cambia (restablecer vuelve a él). Null en DATA. */
  @Column(name = "cluster_original_id", updatable = false)
  private Long clusterOriginalId;

  /** Columna generada (STORED): cluster_id IS DISTINCT FROM cluster_original_id (false en DATA). Solo lectura. */
  @Column(name = "cluster_cambiado", insertable = false, updatable = false)
  private boolean clusterCambiado;

  /**
   * Grupo de rutas dentro de su cluster (se asigna en DATA). El balanceo no lo cambia: reparte
   * cada grupo en su propio tramo de chutes. Null solo en DATA.
   */
  @Column(name = "grupo", length = 50, updatable = false)
  private String grupo;

  /**
   * Días de la ruta (se asigna en DATA): DIARIA aparece en los dos balanceos; LXV o MJS solo en
   * el suyo. Ver {@link Balanceo}. Null solo en DATA.
   */
  @Column(name = "frecuencia", length = 10, updatable = false)
  private String frecuencia;

  /** Días tal como vienen en el Excel («L-X», «V», «L-X-V»); null en rutas anteriores a las frecuencias. */
  @Column(name = "dias", length = 20, updatable = false)
  private String dias;

  @Column(name = "codigo_ruta", length = 50, nullable = false)
  private String codigoRuta;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", length = 10)
  private TipoRuta tipo;

  @Column(name = "total_bultos", precision = 12, scale = 2, nullable = false)
  private BigDecimal totalBultos;

  @Column(name = "total_unidades", precision = 12, scale = 2, nullable = false)
  private BigDecimal totalUnidades;

  @Column(name = "total_lineas", precision = 12, scale = 2, nullable = false)
  private BigDecimal totalLineas;

  @Column(name = "conteo_cod", nullable = false)
  private Integer conteoCod;
}
