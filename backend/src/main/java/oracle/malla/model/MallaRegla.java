package oracle.malla.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import oracle.common.model.Auditable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Regla operativa de la Malla. El catálogo de reglas vive en la base de datos
 * (no en el código) para que un administrador pueda activarlas o ajustar sus
 * parámetros desde el módulo Configuración. El evaluador conoce el código de
 * cada regla y cómo interpretar sus parámetros.
 */
@Entity
@Table(schema = "malla", name = "malla_regla")
@Getter
@Setter
public class MallaRegla extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo", length = 40, nullable = false, unique = true)
    private String codigo;

    @Column(name = "nombre", length = 120, nullable = false)
    private String nombre;

    @Column(name = "descripcion", length = 400, nullable = false)
    private String descripcion;

    /** CENTRO, TURNO, FLUJO, OLA, ZONA, CAPACIDAD o DOTACION. */
    @Column(name = "ambito", length = 20, nullable = false)
    private String ambito;

    /** ALTA, MEDIA o BAJA. */
    @Column(name = "severidad", length = 10, nullable = false)
    private String severidad;

    /** Parámetros efectivos (JSONB): {"clave":"valor"}. Se lee y escribe como texto JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parametros", nullable = false)
    private String parametros;

    @Column(name = "activa", nullable = false)
    private boolean activa;

    @Column(name = "orden", nullable = false)
    private int orden;
}
