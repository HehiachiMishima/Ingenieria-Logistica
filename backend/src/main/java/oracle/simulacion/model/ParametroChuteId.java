package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Clave compuesta de {@link ParametroChute}. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ParametroChuteId implements Serializable {

  @Column(name = "archivo_id")
  private Long archivoId;

  @Column(name = "chute_id")
  private Long chuteId;

  @Override
  public boolean equals(Object otro) {
    if (this == otro) {
      return true;
    }
    if (!(otro instanceof ParametroChuteId that)) {
      return false;
    }
    return Objects.equals(archivoId, that.archivoId) && Objects.equals(chuteId, that.chuteId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(archivoId, chuteId);
  }
}
