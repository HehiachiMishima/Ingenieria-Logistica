package oracle.simulacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Catálogo de clusters (CL1, CL2, CP3, ...). */
@Entity
@Table(schema = "simulacion", name = "clusters")
@Getter
@Setter
public class Cluster {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "nombre", length = 50, nullable = false, unique = true)
  private String nombre;

  @Column(name = "activo", nullable = false)
  private Boolean activo = Boolean.TRUE;
}
