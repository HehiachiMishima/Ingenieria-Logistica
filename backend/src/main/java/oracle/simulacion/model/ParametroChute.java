package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Operarios base de un chute dentro de un archivo: los que fija el usuario.
 * Lo que asigna el paso 2 va por ola en {@link OperarioOla}.
 */
@Entity
@Table(schema = "simulacion", name = "parametros_chutes")
@Getter
@Setter
public class ParametroChute {

  /**
   * Operarios con los que arranca cada chute al cargar un Excel. Por defecto
   * ninguno: el balanceo reparte el pool global según los topes.
   */
  public static final int OPERARIOS_INICIALES = 0;

  @EmbeddedId
  private ParametroChuteId id;

  /**
   * Base del usuario (al cargar, 0): los operarios de la proyección, el «antes»
   * del comparativo. El balanceo no la usa para repartir rutas ni la cambia.
   */
  @Column(name = "operarios", nullable = false)
  private Integer operarios;
}
