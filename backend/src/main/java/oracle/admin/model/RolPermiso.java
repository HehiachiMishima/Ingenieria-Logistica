package oracle.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;

@Entity
@Table(schema = "seguridad", name = "roles_permisos")
@Getter
@Setter
/** Plantilla RBAC. No extiende Auditable: la tabla solo tiene creado_*. */
public class RolPermiso {

    @EmbeddedId
    private RolPermisoId id = new RolPermisoId();

    @ManyToOne
    @MapsId("rolId")
    @JoinColumn(name = "rol_id")
    private Rol rol;

    @ManyToOne
    @MapsId("permisoId")
    @JoinColumn(name = "permiso_id")
    private Permiso permiso;

    @Column(name = "creado_por", nullable = false)
    private Long creadoPor;

    @CreatedDate
    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Getter
    @Setter
    @EqualsAndHashCode
    public static class RolPermisoId implements Serializable {
        private static final long serialVersionUID = 1L;

        @Column(name = "rol_id")
        private Long rolId;

        @Column(name = "permiso_id")
        private Long permisoId;
    }
}
