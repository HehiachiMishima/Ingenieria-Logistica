package oracle.bitacora.controller;

import oracle.bitacora.response.AnaliticaBitacoraResponse;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import oracle.bitacora.request.FiltroIncidencia;
import oracle.bitacora.request.IncidenciaActualizarRequest;
import oracle.bitacora.request.IncidenciaCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.bitacora.response.IncidenciaResponse;
import oracle.common.response.PaginaResponse;
import oracle.bitacora.service.IncidenciaService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** CRUD de incidencias (bitácora), con listado paginado y filtrado en el servidor. */
@RestController
@RequestMapping("/api/incidencias")
public class IncidenciaController {

    private final IncidenciaService incidenciaService;

    public IncidenciaController(IncidenciaService incidenciaService) {
        this.incidenciaService = incidenciaService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VER_INCIDENCIAS')")
    public Mono<ApiResponse<PaginaResponse<IncidenciaResponse>>> listar(
            @RequestParam(required = false) Long centro,
            @RequestParam(required = false) Long sistema,
            @RequestParam(required = false) Long area,
            @RequestParam(required = false) Long responsable,
            @RequestParam(required = false) Long tipo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) String texto,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "10") int tamano) {
        return incidenciaService.listar(
                new FiltroIncidencia(centro, sistema, area, responsable, tipo, desde, hasta, texto),
                pagina, tamano);
    }

    /**
     * Exporta en flujo (chunked) el JSON completo del filtro. El envoltorio es
     * el mismo que el de una respuesta normal, pero las filas se escriben por
     * lotes en vez de cargarse todas en memoria.
     *
     * Se escribe en el hilo de la petición (virtual) y no como
     * StreamingResponseBody asíncrono: el timeout async (15 s) cortaba a la
     * mitad los exports grandes y el cliente recibía un JSON truncado.
     */
    @GetMapping("/exportar")
    @PreAuthorize("hasAuthority('VER_INCIDENCIAS')")
    public void exportar(
            @RequestParam(required = false) Long centro,
            @RequestParam(required = false) Long sistema,
            @RequestParam(required = false) Long area,
            @RequestParam(required = false) Long responsable,
            @RequestParam(required = false) Long tipo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) String texto,
            HttpServletResponse respuesta) throws IOException {
        respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        incidenciaService.exportarEnFlujo(
                        new FiltroIncidencia(centro, sistema, area, responsable, tipo, desde, hasta, texto))
                .writeTo(respuesta.getOutputStream());
    }

    /** Analitica del dashboard (KPIs, rankings y cruces) para el filtro. */
    @GetMapping("/analitica")
    @PreAuthorize("hasAuthority('VER_DASHBOARD')")
    public Mono<ApiResponse<AnaliticaBitacoraResponse>> analitica(
            @RequestParam(required = false) Long centro,
            @RequestParam(required = false) Long sistema,
            @RequestParam(required = false) Long area,
            @RequestParam(required = false) Long responsable,
            @RequestParam(required = false) Long tipo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) String texto) {
        return incidenciaService.analitica(
                new FiltroIncidencia(centro, sistema, area, responsable, tipo, desde, hasta, texto));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREAR_INCIDENCIAS')")
    public Mono<ApiResponse<IncidenciaResponse>> crear(@Valid @RequestBody IncidenciaCrearRequest peticion) {
        return incidenciaService.crear(peticion);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_INCIDENCIAS')")
    public Mono<ApiResponse<IncidenciaResponse>> actualizar(@PathVariable Long id,
            @Valid @RequestBody IncidenciaActualizarRequest peticion) {
        return incidenciaService.actualizar(id, peticion);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ELIMINAR_INCIDENCIAS')")
    public Mono<ApiResponse<Void>> eliminar(@PathVariable Long id) {
        return incidenciaService.eliminar(id);
    }
}
