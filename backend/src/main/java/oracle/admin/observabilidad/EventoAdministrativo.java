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
    /** El «Éxito» quedó confirmado (commit): ya no se registra un fallo para esta petición. */
    boolean confirmado;
    /** Ya se escribió el «Éxito» en la transacción en curso (la solicitud es UNIQUE: una fila por petición). */
    private boolean registrado;

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

    /**
     * Solo las escrituras identificadas por el interceptor generan auditoría transaccional. Se
     * escribe una vez por petición aunque la operación abra (o anide) varias transacciones; si la
     * transacción revierte, la fila se deshace con el cambio y la petición vuelve a poder registrarlo.
     */
    public static void registrarCambio(Object respuesta) {
        var atributos = RequestContextHolder.getRequestAttributes();
        if (atributos == null || !(atributos.getAttribute(CLAVE, RequestAttributes.SCOPE_REQUEST)
                instanceof EventoAdministrativo evento) || evento.registrado) return;
        Object datos = respuesta instanceof ApiResponse<?> api ? api.datos() : respuesta;
        String objetivo = switch (datos) {
            case UsuarioResponse u -> u.id().toString();
            case RolResponse r -> r.id().toString();
            case PermisoResponse p -> p.id().toString();
            case UsuarioRolResponse r -> r.usuarioId() + "/" + r.rolId();
            case RolPermisoResponse r -> r.rolId() + "/" + r.permisoId();
            case UsuarioPermisoResponse r -> r.usuarioId() + "/" + r.permisoId() + (r.otorgado() ? " concedido" : " denegado");
            case null, default -> evento.conCreado(datos);
        };
        evento.insertar("Éxito", objetivo);
        evento.registrado = true;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evento.confirmado = true; // sin transacción: el INSERT ya quedó escrito
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int estado) {
                if (estado == STATUS_COMMITTED) evento.confirmado = true;
                else evento.registrado = false;
            }
        });
    }

    /**
     * Registro de la ruta; en un alta se le suma el id creado («345» al subir un archivo,
     * «5/12» al crear el responsable 12 del área 5). Se lee del resultado (record con id() o
     * entidad con getId()) para no atar la auditoría a los tipos de cada módulo.
     */
    private String conCreado(Object datos) {
        if (!accion.equals("Crear") && !accion.equals("Subir")) return registro;
        String id = idDe(datos);
        if (id == null) return registro;
        return registro.isEmpty() ? id : registro + "/" + id;
    }

    private static String idDe(Object datos) {
        if (datos == null) return null;
        for (String accesor : new String[] {"id", "getId"}) {
            try {
                if (datos.getClass().getMethod(accesor).invoke(datos) instanceof Number n) return n.toString();
            } catch (ReflectiveOperationException | RuntimeException sinId) {
                // El resultado no expone ese accesor: se prueba el siguiente.
            }
        }
        return null;
    }

    void insertar(String resultado, String objetivo) {
        jdbc.update("""
            INSERT INTO bitacora.auditoria_eventos
              (solicitud,actor_id,usuario,accion,entidad,registro,resultado,correlacion)
            VALUES (?,?,?,?,?,?,?,?)
            """, solicitud, actor, usuario, accion, entidad, objetivo, resultado, correlacion);
    }
}
