package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Clave compuesta de {@link ParametroCluster}. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ParametroClusterId implements Serializable {

  @Column(name = "archivo_id")
  private Long archivoId;

  @Column(name = "cluster_id")
  private Long clusterId;

  @Override
  public boolean equals(Object otro) {
    if (this == otro) {
      return true;
    }
    if (!(otro instanceof ParametroClusterId that)) {
      return false;
    }
    return Objects.equals(archivoId, that.archivoId) && Objects.equals(clusterId, that.clusterId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(archivoId, clusterId);
  }
}
