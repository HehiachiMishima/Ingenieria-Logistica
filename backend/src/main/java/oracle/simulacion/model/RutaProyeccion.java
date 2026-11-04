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

/** Ruta proyectada de un archivo (agrupada desde el Excel). */
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

  /** Chute al que pertenecía la ruta al cargar el Excel (no lo altera el balanceo). */
  @Column(name = "chute_original_id", nullable = false)
  private Long chuteOriginalId;

  /**
   * Columna generada (STORED) en PostgreSQL (chute_id <> chute_original_id) con índice parcial: las
   * consultas de rutas movidas no recorren todo el registro. Solo lectura.
   */
  @Column(name = "movida", insertable = false, updatable = false)
  private boolean movida;

  @Column(name = "cluster_id", nullable = false)
  private Long clusterId;

  /** Cluster al cargar el Excel: no cambia nunca (restablecer vuelve a él). */
  @Column(name = "cluster_original_id", nullable = false, updatable = false)
  private Long clusterOriginalId;

  /** Columna generada (STORED): cluster_id <> cluster_original_id. Solo lectura. */
  @Column(name = "cluster_cambiado", insertable = false, updatable = false)
  private boolean clusterCambiado;

  @Column(name = "codigo_ruta", length = 50, nullable = false)
  private String codigoRuta;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", length = 10, nullable = false)
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
