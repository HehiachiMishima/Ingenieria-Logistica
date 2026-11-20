package oracle.admin.observabilidad;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import oracle.common.config.RegistroPeticiones;
import oracle.common.support.ActorActual;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Conserva la identidad verificada antes de que Spring Security limpie el contexto. */
@Component
@Configuration
public class TrazaApi implements WebMvcConfigurer, HandlerInterceptor {
    private static final String ACTOR = TrazaApi.class.getName() + ".actor";
    private static final String USUARIO = TrazaApi.class.getName() + ".usuario";
    private static final String SOLICITUD = TrazaApi.class.getName() + ".solicitud";
    /** Acción y entidad con que se audita una escritura; la clave es «Controlador.método». */
    private record Operacion(String accion, String entidad) {}

    /**
     * Las escrituras que cuentan como cambio en Auditoría y en Actividad. Lo que no está aquí
     * (lecturas, validar o previsualizar un archivo, la sesión) no genera evento. Cada método
     * listado abre su transacción con {@code Transacciones.ejecutar}, que registra el «Éxito».
     */
    private static final Map<String, Operacion> OPERACIONES = operaciones();

    /** Variables de ruta que identifican el registro, en este orden («usuario/rol», «área/responsable»…). */
    private static final List<String> IDS_REGISTRO =
        List.of("id", "usuarioId", "rolId", "permisoId", "areaId", "responsableId", "sistemaId", "tipoId");

    private static Map<String, Operacion> operaciones() {
        var m = new HashMap<String, Operacion>();
        // Control de accesos
        for (var c : List.of("UsuarioController:Usuario", "RolController:Rol", "PermisoController:Permiso")) {
            String[] p = c.split(":");
            agregar(m, p[0], p[1], "crear", "Crear", "actualizar", "Actualizar",
                "cambiarActivo", "Cambiar estado", "eliminar", "Eliminar");
        }
        agregar(m, "UsuarioRolController", "Rol de usuario", "asignar", "Asignar", "quitar", "Quitar",
            "actualizarLote", "Actualizar roles");
        agregar(m, "RolPermisoController", "Permiso de rol", "asignar", "Asignar", "quitar", "Quitar",
            "actualizarLote", "Actualizar permisos");
        agregar(m, "UsuarioPermisoController", "Permiso de usuario", "otorgar", "Cambiar permiso", "eliminar", "Eliminar");
        agregar(m, "AuthController", "Cuenta", "cambiarContrasena", "Cambiar contraseña");
        // Simulación Titan: el registro es siempre el archivo
        agregar(m, "SimulacionController", "Archivo Titan",
            "subir", "Subir", "actualizar", "Actualizar", "eliminar", "Eliminar",
            "guardarConfiguracion", "Guardar configuración", "moverRuta", "Mover ruta", "moverRutas", "Mover rutas",
            "cambiarClusterRuta", "Cambiar cluster de ruta", "balancear", "Balancear cluster",
            "restablecerCluster", "Restablecer cluster", "cerrar", "Cerrar cluster", "reabrir", "Reabrir cluster",
            "guardarBalanceoRutas", "Guardar predeterminado", "cargarBalanceoRutas", "Cargar predeterminado",
            "restablecer", "Restablecer archivo");
        agregar(m, "SimulacionPredeterminadosController", "Predeterminados Titan",
            "bloquear", "Bloquear", "desbloquear", "Desbloquear");
        // Simulación Santa Anita
        agregar(m, "SimulacionSantaController", "Archivo Santa Anita",
            "subir", "Subir", "renombrar", "Renombrar", "eliminar", "Eliminar",
            "guardarConfiguracion", "Guardar configuración", "cambiarEnvio", "Cambiar envío");
        agregar(m, "SimulacionSantaPredeterminadosController", "Predeterminados Santa Anita",
            "bloquear", "Bloquear", "desbloquear", "Desbloquear");
        // Bitácora
        agregar(m, "IncidenciaController", "Incidencia",
            "crear", "Crear", "actualizar", "Actualizar", "cerrar", "Cerrar", "eliminar", "Eliminar");
        agregar(m, "AreaController", "Área", "crear", "Crear", "actualizar", "Actualizar", "eliminar", "Eliminar");
        agregar(m, "AreaController", "Responsable de área", "crearResponsable", "Crear",
            "actualizarResponsable", "Actualizar", "eliminarResponsable", "Eliminar");
        agregar(m, "SistemaController", "Sistema", "crear", "Crear", "actualizar", "Actualizar", "eliminar", "Eliminar");
        agregar(m, "SistemaController", "Tipo de incidencia", "crearTipo", "Crear",
            "actualizarTipo", "Actualizar", "eliminarTipo", "Eliminar");
        agregar(m, "CentroController", "Centro", "crear", "Crear", "actualizar", "Actualizar", "eliminar", "Eliminar");
        // Malla
        agregar(m, "MallaCentroController", "Centro de malla",
            "crear", "Crear", "guardar", "Guardar malla", "eliminar", "Eliminar");
        agregar(m, "MallaConfiguracionController", "Regla de malla", "actualizar", "Actualizar");
        return Map.copyOf(m);
    }

    private static void agregar(Map<String, Operacion> m, String controlador, String entidad, String... metodoYAccion) {
        for (int i = 0; i < metodoYAccion.length; i += 2) {
            if (m.put(controlador + "." + metodoYAccion[i], new Operacion(metodoYAccion[i + 1], entidad)) != null) {
                throw new IllegalStateException("Operación auditada repetida: " + controlador + "." + metodoYAccion[i]);
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final ColaPeticiones trazas;
    public TrazaApi(JdbcTemplate jdbc, ColaPeticiones trazas) {
        this.jdbc = jdbc;
        this.trazas = trazas;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod h)) return true;
        var principal = req.getUserPrincipal();
        Long actor = principal == null ? null : ActorActual.id();
        String usuario = principal == null ? null : principal.getName();
        req.setAttribute(ACTOR, actor);
        req.setAttribute(USUARIO, usuario);
        UUID solicitud = UUID.randomUUID();
        req.setAttribute(SOLICITUD, solicitud);
        var operacion = OPERACIONES.get(h.getBeanType().getSimpleName() + "." + h.getMethod().getName());
        if (operacion != null && !req.getMethod().equals("GET")) {
            @SuppressWarnings("unchecked")
            var ids = (Map<String,String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            String registro = ids == null ? "" : IDS_REGISTRO.stream()
                .map(ids::get).filter(v -> v != null && v.matches("[0-9]{1,19}"))
                .collect(Collectors.joining("/"));
            req.setAttribute(EventoAdministrativo.CLAVE, new EventoAdministrativo(jdbc, solicitud, actor, usuario,
                operacion.accion(), operacion.entidad(), registro, MDC.get(RegistroPeticiones.MDC_CORRELACION)));
        }
        return true;
    }

    public void completar(HttpServletRequest req, int estado, long ms, String correlacion) {
        Object patron = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        UUID solicitud = (UUID) req.getAttribute(SOLICITUD);
        trazas.ofrecer(new ColaPeticiones.Traza(solicitud == null ? UUID.randomUUID() : solicitud,
            (Long) req.getAttribute(ACTOR), (String) req.getAttribute(USUARIO), req.getMethod(),
            patron == null ? "(sin coincidencia)" : patron.toString(), estado, ms, correlacion, Instant.now()));
        var evento = (EventoAdministrativo) req.getAttribute(EventoAdministrativo.CLAVE);
        if (evento != null && !evento.confirmado && estado >= 400) {
            evento.insertar(estado == 401 || estado == 403 ? "Denegado" : "Fallo", evento.registro);
        }
    }
}
