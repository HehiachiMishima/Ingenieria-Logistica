package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * Tiempo límite (horas) de un cluster dentro de un archivo y su avance. Los
 * clusters se trabajan de a uno, en el orden de las olas: hasta cerrar uno no
 * se puede empezar el siguiente.
 */
@Entity
@Table(schema = "simulacion", name = "parametros_clusters")
@Getter
@Setter
public class ParametroCluster {

  /** Sin lanzar. */
  public static final String ESTADO_PENDIENTE = "PENDIENTE";
  /** Paso 1 hecho: rutas de la ola repartidas; faltan sus operarios. */
  public static final String ESTADO_RUTAS = "RUTAS";
  /** Paso 2 hecho: la ola tiene sus operarios en operarios_ola. */
  public static final String ESTADO_LISTO = "LISTO";
  /** Terminado: el equipo pasa al siguiente cluster con estos operarios. */
  public static final String ESTADO_CERRADO = "CERRADO";

  @EmbeddedId
  private ParametroClusterId id;

  @Column(name = "limite_horas", precision = 10, scale = 2, nullable = false)
  private BigDecimal limiteHoras;

  @Column(name = "estado", length = 20, nullable = false)
  private String estado = ESTADO_PENDIENTE;
}
