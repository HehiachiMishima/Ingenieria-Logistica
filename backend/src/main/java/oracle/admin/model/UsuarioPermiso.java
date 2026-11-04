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
import org.springframework.data.annotation.LastModifiedDate;

@Entity
@Table(schema = "seguridad", name = "usuarios_permisos")
@Getter
@Setter
public class UsuarioPermiso {

    @EmbeddedId
    private UsuarioPermisoId id = new UsuarioPermisoId();

    @ManyToOne
    @MapsId("usuarioId")
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne
    @MapsId("permisoId")
    @JoinColumn(name = "permiso_id")
    private Permiso permiso;

    @Column(name = "otorgado", nullable = false)
    private Boolean otorgado;

    @Column(name = "creado_por", nullable = false)
    private Long creadoPor;

    @Column(name = "actualizado_por", nullable = false)
    private Long actualizadoPor;

    @CreatedDate
    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @LastModifiedDate
    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Getter
    @Setter
    @EqualsAndHashCode
    public static class UsuarioPermisoId implements Serializable {
        private static final long serialVersionUID = 1L;

        @Column(name = "usuario_id")
        private Long usuarioId;

        @Column(name = "permiso_id")
        private Long permisoId;
    }
}
