package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Operarios que el paso 2 asignó a un chute en la ola de un cluster. Solo
 * existen para clusters LISTO o CERRADO: cada ola tiene su dotación, y la del
 * cluster cerrado es la que recibe el siguiente.
 */
@Entity
@Table(schema = "simulacion", name = "operarios_ola")
@Getter
@Setter
public class OperarioOla {

  @EmbeddedId
  private OperarioOlaId id;

  @Column(name = "operarios", nullable = false)
  private Integer operarios;
}
