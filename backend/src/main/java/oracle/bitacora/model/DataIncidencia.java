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
@Table(schema = "bitacora", name = "data_incidencia")
@Getter
@Setter
public class DataIncidencia extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "id_data_sistema", nullable = false, updatable = false)
    private Long idDataSistema;

    @Column(name = "incidencia", nullable = false, length = 50)
    private String incidencia;
}
