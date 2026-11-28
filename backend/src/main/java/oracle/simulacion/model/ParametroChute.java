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

  /**
   * Chute con más capacidad: al balancear recibe primero las rutas más pesadas
   * de la ola. Se define al inicio, para todo el registro.
   */
  @Column(name = "buff", nullable = false)
  private boolean buff;

  /**
   * Chute bloqueado (la franja de peligro): el balanceo automático, el rango y el reparto al azar
   * de DATA no lo usan; recibe rutas solo a mano y lo que se deja ahí el balanceo no lo mueve. Se
   * elige en Configuración («Chutes bloqueados») y se puede cambiar en cualquier momento: vale
   * para los balanceos siguientes.
   */
  @Column(name = "manual", nullable = false)
  private boolean manual;
}
