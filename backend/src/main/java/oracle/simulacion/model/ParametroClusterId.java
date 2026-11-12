package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Clave compuesta de {@link ParametroCluster}: cada cluster tiene una fila por balanceo. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class ParametroClusterId implements Serializable {

  @Column(name = "archivo_id")
  private Long archivoId;

  /** LXV o MJS. */
  @Column(name = "balanceo", length = 3)
  private String balanceo;

  @Column(name = "cluster_id")
  private Long clusterId;

  public ParametroClusterId(Long archivoId, String balanceo, Long clusterId) {
    this.archivoId = archivoId;
    this.balanceo = balanceo;
    this.clusterId = clusterId;
  }

  /** Fila del balanceo L-X-V. */
  public ParametroClusterId(Long archivoId, Long clusterId) {
    this(archivoId, Balanceo.LXV.name(), clusterId);
  }

  @Override
  public boolean equals(Object otro) {
    if (this == otro) {
      return true;
    }
    if (!(otro instanceof ParametroClusterId that)) {
      return false;
    }
    return Objects.equals(archivoId, that.archivoId)
        && Objects.equals(balanceo, that.balanceo)
        && Objects.equals(clusterId, that.clusterId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(archivoId, balanceo, clusterId);
  }
}
