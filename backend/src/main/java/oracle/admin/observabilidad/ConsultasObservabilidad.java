package oracle.admin.observabilidad;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class ConsultasObservabilidad {
    private final JdbcTemplate jdbc;
    private final ResumenObservabilidad resumen;
    private final int retencion;
    private static final int PAGINA = 50;
    private static final Set<String> RESULTADOS = Set.of("Éxito", "Fallo", "Denegado");

    public ConsultasObservabilidad(JdbcTemplate jdbc, ResumenObservabilidad resumen,
                                   @Value("${app.monitoreo.retencion-dias:30}") int dias) {
        this.jdbc = jdbc;
        this.resumen = resumen;
        if (dias < 1) throw new IllegalArgumentException("La retención de peticiones debe ser positiva.");
        retencion = dias;
    }

    public record Pagina(List<Map<String,Object>> filas, Long siguiente) {}

    private static class Filtro {
        final StringBuilder sql = new StringBuilder();
        final List<Object> args = new ArrayList<>();
        Filtro(String consulta, String alias, int dias, Long antes) {
            if (dias < 1 || dias > 90) throw new SolicitudInvalidaException("El período debe ser de 1 a 90 días.");
            sql.append(consulta).append(" WHERE ").append(alias).append(".ocurrido_en >= now() - (? * interval '1 day')");
            args.add(dias);
            if (antes != null) {
                sql.append(" AND ").append(alias).append(".id < ?");
                args.add(antes);
            }
        }
        void igual(String columna, String valor) {
            if (valor != null && !valor.isBlank()) {
                if (valor.length() > 250) throw new SolicitudInvalidaException("Filtro demasiado largo.");
                sql.append(" AND ").append(columna).append(" = ?");
                args.add(valor.strip());
            }
        }
    }

    private ApiResponse<Pagina> pagina(Filtro f, String alias) {
        f.sql.append(" ORDER BY ").append(alias).append(".id DESC LIMIT ").append(PAGINA + 1);
        var filas = jdbc.queryForList(f.sql.toString(), f.args.toArray());
        boolean mas = filas.size() > PAGINA;
        return ApiResponse.ok("Consulta completada.", new Pagina(mas ? filas.subList(0, PAGINA) : filas,
            mas ? ((Number) filas.get(PAGINA - 1).get("id")).longValue() : null));
    }

    @GetMapping("/auditoria")
    @PreAuthorize("hasAuthority('VER_AUDITORIA')")
    public ApiResponse<Pagina> auditoria(@RequestParam(defaultValue="7") int dias,
            @RequestParam(required=false) String usuario, @RequestParam(required=false) String registro,
            @RequestParam(required=false) String resultado, @RequestParam(required=false) Long antes) {
        if (resultado != null && !resultado.isBlank() && !RESULTADOS.contains(resultado)) {
            throw new SolicitudInvalidaException("Resultado no válido: usa Éxito, Fallo o Denegado.");
        }
        var f = new Filtro("""
            SELECT a.id,a.ocurrido_en AS fecha,a.usuario,a.accion,a.entidad,a.registro,a.resultado,
                   a.correlacion,p.metodo,p.ruta,p.estado,p.duracion_ms
            FROM bitacora.auditoria_eventos a LEFT JOIN bitacora.peticiones p USING(solicitud)
            """, "a", dias, antes);
        f.igual("a.usuario", usuario);
        f.igual("a.registro", registro);
        f.igual("a.resultado", resultado);
        return pagina(f, "a");
    }

    @GetMapping("/actividad")
    @PreAuthorize("hasAuthority('VER_ACTIVIDAD')")
    public ApiResponse<Pagina> actividad(@RequestParam(defaultValue="7") int dias,
            @RequestParam(required=false) String usuario, @RequestParam(required=false) Long antes) {
        var f = new Filtro("""
            SELECT p.id,p.ocurrido_en AS fecha,p.usuario,p.metodo,p.ruta,p.estado,p.duracion_ms,
                   p.correlacion,a.accion,a.entidad,a.registro,a.resultado
            FROM bitacora.peticiones p LEFT JOIN bitacora.auditoria_eventos a USING(solicitud)
            """, "p", dias, antes);
        f.sql.append(" AND p.actor_id IS NOT NULL AND p.ruta NOT IN (").append(ColaPeticiones.RUTAS_TECNICAS).append(")");
        f.igual("p.usuario", usuario);
        return pagina(f, "p");
    }

    @GetMapping("/monitoreo")
    @PreAuthorize("hasAuthority('VER_MONITOREO')")
    /** Una fila por API desde el resumen por hora: el p95 es aproximado por intervalos. */
    public ApiResponse<List<ResumenObservabilidad.EndpointMonitoreo>> monitoreo(@RequestParam(defaultValue="1") int dias) {
        return ApiResponse.ok("Monitoreo por endpoint.", resumen.endpoints(dias));
    }

    @GetMapping("/monitoreo/resumen")
    @PreAuthorize("hasAuthority('VER_MONITOREO')")
    public ApiResponse<ResumenObservabilidad.ResumenMonitoreo> resumenMonitoreo(@RequestParam(defaultValue="1") int dias) {
        return ApiResponse.ok("Resumen de monitoreo.", resumen.monitoreo(dias));
    }

    @GetMapping("/actividad/resumen")
    @PreAuthorize("hasAuthority('VER_ACTIVIDAD')")
    public ApiResponse<ResumenObservabilidad.ResumenActividad> resumenActividad(@RequestParam(defaultValue="7") int dias,
            @RequestParam(required=false) String usuario) {
        return ApiResponse.ok("Resumen de actividad.", resumen.actividad(dias, usuario));
    }

    @GetMapping("/auditoria/resumen")
    @PreAuthorize("hasAuthority('VER_AUDITORIA')")
    public ApiResponse<ResumenObservabilidad.ResumenAuditoria> resumenAuditoria(@RequestParam(defaultValue="7") int dias,
            @RequestParam(required=false) String usuario, @RequestParam(required=false) String registro) {
        return ApiResponse.ok("Resumen de auditoría.", resumen.auditoria(dias, usuario, registro));
    }

    @GetMapping("/monitoreo/peticiones")
    @PreAuthorize("hasAuthority('VER_MONITOREO')")
    public ApiResponse<Pagina> peticiones(@RequestParam(defaultValue="1") int dias, @RequestParam String metodo,
            @RequestParam String ruta, @RequestParam(defaultValue="false") boolean errores, @RequestParam(required=false) Long antes) {
        var f = new Filtro("""
            SELECT p.id,p.ocurrido_en AS fecha,p.metodo,p.ruta,p.estado,p.duracion_ms,p.correlacion
            FROM bitacora.peticiones p
            """, "p", dias, antes);
        f.igual("p.metodo", metodo);
        f.igual("p.ruta", ruta);
        if (errores) f.sql.append(" AND p.estado >= 400");
        return pagina(f, "p");
    }

    @Scheduled(initialDelayString="PT5M", fixedDelayString="PT1M")
    public void purgarPeticiones() {
        jdbc.update("""
            DELETE FROM bitacora.peticiones WHERE id IN (
                SELECT id FROM bitacora.peticiones WHERE ocurrido_en < now() - (? * interval '1 day')
                ORDER BY ocurrido_en,id LIMIT 5000)
            """, retencion);
        // Los resúmenes por hora son pocas filas (horas × APIs) y viven más que las trazas.
        jdbc.update("DELETE FROM bitacora.peticiones_hora WHERE hora < now() - make_interval(days => ?)",
            ResumenObservabilidad.RETENCION_RESUMEN_DIAS);
        jdbc.update("DELETE FROM bitacora.actividad_hora WHERE hora < now() - make_interval(days => ?)",
            ResumenObservabilidad.RETENCION_RESUMEN_DIAS);
    }
}
