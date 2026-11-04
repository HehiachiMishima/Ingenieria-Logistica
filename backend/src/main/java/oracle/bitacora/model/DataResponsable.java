package oracle.bitacora.model;

import oracle.common.model.Auditable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(schema = "bitacora", name = "data_responsable")
@Getter
@Setter
public class DataResponsable extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "id_data_area", nullable = false, updatable = false)
    private Long idDataArea;

    @Column(name = "responsable", nullable = false, unique = true, length = 50)
    private String responsable;
}
