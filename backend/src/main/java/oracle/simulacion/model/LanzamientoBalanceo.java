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

/** Un lanzamiento de un cluster: paso, resultado, quién y cuándo. */
@Entity
@Table(schema = "simulacion", name = "lanzamientos_balanceo")
@Getter
@Setter
public class LanzamientoBalanceo {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "archivo_id", nullable = false)
  private Long archivoId;

  @Column(name = "cluster_id", nullable = false)
  private Long clusterId;

  /** RUTAS (paso 1) u OPERARIOS (paso 2 o su recálculo). */
  @Column(name = "fase", length = 20, nullable = false)
  private String fase;

  @Column(name = "mensaje", length = 1000, nullable = false)
  private String mensaje;

  @Column(name = "creado_por", nullable = false)
  private Long creadoPor;

  @Column(name = "creado_en", nullable = false)
  private Instant creadoEn;

  /** Rutas movidas (filas del historial), para listar sin contar el historial. */
  @Column(name = "rutas", nullable = false)
  private int rutas;

  /** De esas, las movidas para separar CL1 y CL2. */
  @Column(name = "separaciones", nullable = false)
  private int separaciones;

  /** Chutes cuya dotación cambió (paso 2). */
  @Column(name = "chutes_operarios", nullable = false)
  private int chutesOperarios;
}
