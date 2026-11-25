package oracle.admin.observabilidad;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import oracle.common.response.PaginaResponse;
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
    /** Mismo tamaño por defecto que el paginador estándar; máximo por petición. */
    static final int TAMANO = 10;
    static final int MAX_TAMANO = 50;
    /**
     * El total se cuenta hasta este tope: contar millones de trazas en cada página sería caro y
     * nadie recorre más de 1 000 páginas. Con filtros (usuario, registro, período corto) el total es exacto.
     */
    static final int TOPE_TOTAL = 10_000;
    /** Tope del filtro «Lentas»: una hora en ms (ninguna petición dura más sin cortarse antes). */
    static final long MAX_LENTAS_MS = 3_600_000;
    private static final Set<String> RESULTADOS = Set.of("Éxito", "Fallo", "Denegado");

    public ConsultasObservabilidad(JdbcTemplate jdbc, ResumenObservabilidad resumen,
                                   @Value("${app.monitoreo.retencion-dias:30}") int dias) {
        this.jdbc = jdbc;
        this.resumen = resumen;
        if (dias < 1) throw new IllegalArgumentException("La retención de peticiones debe ser positiva.");
        retencion = dias;
    }

    /** Condiciones sobre la tabla base (alias), por igualdad y con el período obligatorio. */
    private static class Filtro {
        final StringBuilder where = new StringBuilder();
        final List<Object> args = new ArrayList<>();
        Filtro(String alias, int dias) {
            if (dias < 1 || dias > 90) throw new SolicitudInvalidaException("El período debe ser de 1 a 90 días.");
            where.append(alias).append(".ocurrido_en >= now() - (? * interval '1 day')");
            args.add(dias);
        }
        void igual(String columna, String valor) {
            if (valor != null && !valor.isBlank()) {
                if (valor.length() > 250) throw new SolicitudInvalidaException("Filtro demasiado largo.");
                where.append(" AND ").append(columna).append(" = ?");
                args.add(valor.strip());
            }
        }
        /**
         * Usuario sin distinguir mayúsculas ni tildes: se traduce al login guardado (seguridad.usuarios
         * usa la intercalación comun.ci) y se compara por igualdad, así sigue usando el índice
         * (usuario, id DESC). Un usuario ya eliminado se busca tal como se escribió.
         */
        void usuario(String columna, String valor) {
            if (valor != null && !valor.isBlank()) {
                if (valor.length() > 250) throw new SolicitudInvalidaException("Filtro demasiado largo.");
                // Q: uq_usuarios_usuario (usuario) una vez; luego <tabla>_usuario (usuario, id DESC).
                where.append(" AND ").append(columna).append(" = coalesce(")
                    .append("(SELECT u.usuario FROM seguridad.usuarios u WHERE u.usuario = ?), ?) COLLATE \"default\"");
                args.add(valor.strip());
                args.add(valor.strip());
            }
        }
        /** Condición fija escrita en esta clase (nunca con datos del usuario), con sus parámetros. */
        void y(String condicion, Object... valores) {
            where.append(" AND ").append(condicion);
            args.addAll(List.of(valores));
        }
    }

    /**
     * Página {@code pagina} de {@code tamano} filas, de la más reciente a la más antigua, y el total
     * contado solo sobre la tabla base (las uniones son 1:1 por solicitud) hasta {@link #TOPE_TOTAL}.
     */
    private ApiResponse<PaginaResponse<Map<String, Object>>> pagina(String consulta, String base, String alias,
                                                                    Filtro f, int pagina, int tamano) {
        if (pagina < 1) throw new SolicitudInvalidaException("La página empieza en 1.");
        if (tamano < 1 || tamano > MAX_TAMANO) {
            throw new SolicitudInvalidaException("El tamaño de página debe ser de 1 a " + MAX_TAMANO + ".");
        }
        long desde = (long) (pagina - 1) * tamano;
        if (desde >= TOPE_TOTAL) {
            throw new SolicitudInvalidaException("Solo se recorren los " + TOPE_TOTAL
                + " registros más recientes: acota el período o usa los filtros.");
        }
        // Q: <tabla>_fecha (ocurrido_en, id); el LIMIT corta el conteo en el tope.
        Long total = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM " + base + " WHERE " + f.where
            + " LIMIT " + TOPE_TOTAL + ") c", Long.class, f.args.toArray());
        List<Object> args = new ArrayList<>(f.args);
        args.add(tamano);
        args.add(desde);
        var filas = jdbc.queryForList(consulta + " WHERE " + f.where + " ORDER BY " + alias + ".id DESC LIMIT ? OFFSET ?",
            args.toArray());
        return ApiResponse.ok("Consulta completada.", PaginaResponse.of(filas, total == null ? 0 : total, pagina, tamano));
    }

    @GetMapping("/auditoria")
    @PreAuthorize("hasAuthority('VER_AUDITORIA')")
    public ApiResponse<PaginaResponse<Map<String, Object>>> auditoria(@RequestParam(defaultValue="7") int dias,
            @RequestParam(required=false) String usuario, @RequestParam(required=false) String registro,
            @RequestParam(required=false) String resultado, @RequestParam(defaultValue="1") int pagina,
            @RequestParam(defaultValue="" + TAMANO) int tamano) {
        if (resultado != null && !resultado.isBlank() && !RESULTADOS.contains(resultado)) {
            throw new SolicitudInvalidaException("Resultado no válido: usa Éxito, Fallo o Denegado.");
        }
        var f = new Filtro("a", dias);
        f.usuario("a.usuario", usuario);
        f.igual("a.registro", registro);
        f.igual("a.resultado", resultado);
        return pagina("""
            SELECT a.id,a.ocurrido_en AS fecha,a.usuario,a.accion,a.entidad,a.registro,a.resultado,
                   a.correlacion,p.metodo,p.ruta,p.estado,p.duracion_ms
            FROM bitacora.auditoria_eventos a LEFT JOIN bitacora.peticiones p USING(solicitud)
            """, "bitacora.auditoria_eventos a", "a", f, pagina, tamano);
    }

    @GetMapping("/actividad")
    @PreAuthorize("hasAuthority('VER_ACTIVIDAD')")
    public ApiResponse<PaginaResponse<Map<String, Object>>> actividad(@RequestParam(defaultValue="7") int dias,
            @RequestParam(required=false) String usuario, @RequestParam(defaultValue="false") boolean cambios,
            @RequestParam(defaultValue="1") int pagina, @RequestParam(defaultValue="" + TAMANO) int tamano) {
        var f = new Filtro("p", dias);
        f.y("p.actor_id IS NOT NULL AND p.ruta NOT IN (" + ColaPeticiones.RUTAS_TECNICAS + ")");
        f.usuario("p.usuario", usuario);
        if (cambios) {
            // Solo las peticiones con evento de auditoría. El evento se escribe durante la petición y la
            // traza al terminarla: la hora de margen evita perder los del borde del período.
            // Q: auditoria_fecha (ocurrido_en, id) arma el semi-join; luego peticiones_fecha.
            f.y("p.solicitud IN (SELECT a2.solicitud FROM bitacora.auditoria_eventos a2"
                + " WHERE a2.ocurrido_en >= now() - (? * interval '1 day') - interval '1 hour')", dias);
        }
        return pagina("""
            SELECT p.id,p.ocurrido_en AS fecha,p.usuario,p.metodo,p.ruta,p.estado,p.duracion_ms,
                   p.correlacion,a.accion,a.entidad,a.registro,a.resultado
            FROM bitacora.peticiones p LEFT JOIN bitacora.auditoria_eventos a USING(solicitud)
            """, "bitacora.peticiones p", "p", f, pagina, tamano);
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
    public ApiResponse<PaginaResponse<Map<String, Object>>> peticiones(@RequestParam(defaultValue="1") int dias,
            @RequestParam String metodo, @RequestParam String ruta, @RequestParam(defaultValue="false") boolean errores,
            @RequestParam(required=false) Long lentas,
            @RequestParam(defaultValue="1") int pagina, @RequestParam(defaultValue="" + TAMANO) int tamano) {
        var f = new Filtro("p", dias);
        f.igual("p.metodo", metodo);
        f.igual("p.ruta", ruta);
        if (errores) f.y("p.estado >= 400");
        // «Lentas»: más de N ms (el frontend manda el p95 de la API). Mismo plan que «errores»: la página
        // recorre peticiones_pkey hacia atrás y el total se cuenta con el mismo tope (medido en el informe).
        if (lentas != null) {
            if (lentas < 1 || lentas > MAX_LENTAS_MS) {
                throw new SolicitudInvalidaException("La duración mínima debe ser de 1 a " + MAX_LENTAS_MS + " ms.");
            }
            f.y("p.duracion_ms > ?", lentas);
        }
        return pagina("""
            SELECT p.id,p.ocurrido_en AS fecha,p.usuario,p.metodo,p.ruta,p.estado,p.duracion_ms,p.correlacion
            FROM bitacora.peticiones p
            """, "bitacora.peticiones p", "p", f, pagina, tamano);
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
