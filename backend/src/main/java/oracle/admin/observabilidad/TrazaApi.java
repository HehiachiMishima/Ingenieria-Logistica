package oracle.admin.observabilidad;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    private static final Map<String,String> ENTIDADES = Map.of(
        "UsuarioController","Usuario", "RolController","Rol", "PermisoController","Permiso",
        "UsuarioRolController","Rol de usuario", "UsuarioPermisoController","Permiso de usuario",
        "RolPermisoController","Permiso de rol");
    private static final Map<String,String> ACCIONES = Map.of("crear","Crear", "actualizar","Actualizar",
        "cambiarActivo","Cambiar estado", "eliminar","Eliminar", "asignar","Asignar", "quitar","Quitar", "otorgar","Cambiar permiso");
    private static final Map<String,String> TITAN = Map.of("guardarBalanceoRutas","Guardar predeterminado",
        "cargarBalanceoRutas","Cargar predeterminado","restablecer","Restablecer archivo","restablecerCluster","Restablecer cluster");
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
        String controlador = h.getBeanType().getSimpleName();
        String metodo = h.getMethod().getName();
        String entidad = ENTIDADES.get(controlador);
        String accion = ACCIONES.get(metodo);
        if (controlador.equals("SimulacionController")) {
            entidad = "Archivo Titan";
            accion = TITAN.get(metodo);
        }
        if (entidad != null && accion != null && !req.getMethod().equals("GET")) {
            @SuppressWarnings("unchecked")
            var ids = (Map<String,String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            String registro = Stream.of("id", "usuarioId", "rolId", "permisoId")
                .map(ids::get).filter(v -> v != null && v.matches("[0-9]{1,19}"))
                .collect(Collectors.joining("/"));
            req.setAttribute(EventoAdministrativo.CLAVE, new EventoAdministrativo(jdbc, solicitud,
                actor, usuario, accion, entidad, registro, MDC.get(RegistroPeticiones.MDC_CORRELACION)));
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
