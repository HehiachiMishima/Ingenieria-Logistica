package oracle.admin.observabilidad;

import java.util.UUID;
import oracle.admin.response.*;
import oracle.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Una operación administrativa reconocida por el controlador y su ruta. */
public final class EventoAdministrativo {
    static final String CLAVE = EventoAdministrativo.class.getName();
    final UUID solicitud;
    final Long actor;
    final String usuario, accion, entidad, registro, correlacion;
    private final JdbcTemplate jdbc;
    boolean confirmado;

    EventoAdministrativo(JdbcTemplate jdbc, UUID solicitud, Long actor, String usuario, String accion,
                         String entidad, String registro, String correlacion) {
        this.jdbc = jdbc;
        this.solicitud = solicitud;
        this.actor = actor;
        this.usuario = usuario;
        this.accion = accion;
        this.entidad = entidad;
        this.registro = registro;
        this.correlacion = correlacion;
    }

    /** Solo las escrituras identificadas por el interceptor generan auditoría transaccional. */
    public static void registrarCambio(Object respuesta) {
        var atributos = RequestContextHolder.getRequestAttributes();
        if (atributos == null || !(atributos.getAttribute(CLAVE, RequestAttributes.SCOPE_REQUEST)
                instanceof EventoAdministrativo evento)) return;
        Object datos = respuesta instanceof ApiResponse<?> api ? api.datos() : respuesta;
        String objetivo = switch (datos) {
            case UsuarioResponse u -> u.id().toString();
            case RolResponse r -> r.id().toString();
            case PermisoResponse p -> p.id().toString();
            case UsuarioRolResponse r -> r.usuarioId() + "/" + r.rolId();
            case RolPermisoResponse r -> r.rolId() + "/" + r.permisoId();
            case UsuarioPermisoResponse r -> r.usuarioId() + "/" + r.permisoId() + (r.otorgado() ? " concedido" : " denegado");
            case null, default -> evento.registro;
        };
        evento.insertar("Éxito", objetivo);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { evento.confirmado = true; }
        });
    }

    void insertar(String resultado, String objetivo) {
        jdbc.update("""
            INSERT INTO bitacora.auditoria_eventos
              (solicitud,actor_id,usuario,accion,entidad,registro,resultado,correlacion)
            VALUES (?,?,?,?,?,?,?,?)
            """, solicitud, actor, usuario, accion, entidad, objetivo, resultado, correlacion);
    }
}
