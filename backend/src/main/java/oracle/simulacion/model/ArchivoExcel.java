package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import oracle.common.model.Auditable;

/** Registro de un archivo Excel cargado para simulación. */
@Entity
@Table(schema = "simulacion", name = "archivos_excel")
@Getter
@Setter
public class ArchivoExcel extends Auditable {

  /** Ningún cluster lanzado: como se cargó el Excel (o tras «Restablecer»). */
  public static final String ESTADO_PROYECCION = "PROYECCION";
  /** Algún cluster empezado y no todos cerrados. */
  public static final String ESTADO_EN_PROCESO = "EN_PROCESO";
  /** Todos los clusters cerrados: la simulación terminó. */
  public static final String ESTADO_FINALIZADO = "FINALIZADO";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "nombre_registro", length = 150, nullable = false)
  private String nombreRegistro;

  @Column(name = "nombre_archivo", length = 255, nullable = false)
  private String nombreArchivo;

  @Column(name = "etiqueta", length = 50)
  private String etiqueta;

  @Column(name = "color_hex", length = 10)
  private String colorHex;

  @Column(name = "estado", length = 20, nullable = false)
  private String estado;

  // Totales del Excel: el balanceo solo cambia chutes, así que no varían y la
  // lista de registros los lee de aquí en lugar de sumar todas sus rutas.

  @Column(name = "total_rutas", nullable = false)
  private int totalRutas;

  @Column(name = "total_chutes", nullable = false)
  private int totalChutes;

  @Column(name = "total_bultos", precision = 14, scale = 2, nullable = false)
  private BigDecimal totalBultos = BigDecimal.ZERO;

  @Column(name = "total_unidades", precision = 14, scale = 2, nullable = false)
  private BigDecimal totalUnidades = BigDecimal.ZERO;

  @Column(name = "total_lineas", precision = 14, scale = 2, nullable = false)
  private BigDecimal totalLineas = BigDecimal.ZERO;

  @Column(name = "total_cod", nullable = false)
  private int totalCod;
}
