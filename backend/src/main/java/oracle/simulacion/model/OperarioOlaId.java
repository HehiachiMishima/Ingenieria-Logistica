package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Clave compuesta de {@link OperarioOla}: cada balanceo tiene sus propias olas. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class OperarioOlaId implements Serializable {

  @Column(name = "archivo_id")
  private Long archivoId;

  /** LXV o MJS. */
  @Column(name = "balanceo", length = 3)
  private String balanceo;

  @Column(name = "cluster_id")
  private Long clusterId;

  @Column(name = "chute_id")
  private Long chuteId;

  public OperarioOlaId(Long archivoId, String balanceo, Long clusterId, Long chuteId) {
    this.archivoId = archivoId;
    this.balanceo = balanceo;
    this.clusterId = clusterId;
    this.chuteId = chuteId;
  }

  /** Ola del balanceo L-X-V. */
  public OperarioOlaId(Long archivoId, Long clusterId, Long chuteId) {
    this(archivoId, Balanceo.LXV.name(), clusterId, chuteId);
  }

  @Override
  public boolean equals(Object otro) {
    if (this == otro) {
      return true;
    }
    if (!(otro instanceof OperarioOlaId that)) {
      return false;
    }
    return Objects.equals(archivoId, that.archivoId)
        && Objects.equals(balanceo, that.balanceo)
        && Objects.equals(clusterId, that.clusterId)
        && Objects.equals(chuteId, that.chuteId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(archivoId, balanceo, clusterId, chuteId);
  }
}
