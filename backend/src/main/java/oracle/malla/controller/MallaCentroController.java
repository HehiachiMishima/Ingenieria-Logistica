package oracle.malla.controller;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import oracle.common.response.ApiResponse;
import oracle.malla.request.CentroMallaRequest;
import oracle.malla.response.CambioMallaResponse;
import oracle.malla.response.CentroMallaResponse;
import oracle.malla.response.CentroMallaResumenResponse;
import oracle.malla.service.MallaCentroService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

/**
 * Datos de la malla por centro: el módulo Configuración los edita (formulario
 * o Excel) y el módulo Malla los lee.
 */
@RestController
@RequestMapping("/api/malla/centros")
public class MallaCentroController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final MallaCentroService service;

    public MallaCentroController(MallaCentroService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VER_MALLA')")
    public Mono<ApiResponse<List<CentroMallaResumenResponse>>> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VER_MALLA')")
    public Mono<ApiResponse<CentroMallaResponse>> obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MODIFICAR_MALLA')")
    public Mono<ApiResponse<CentroMallaResponse>> crear(@Valid @RequestBody CentroMallaRequest req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_MALLA')")
    public Mono<ApiResponse<CentroMallaResponse>> guardar(@PathVariable Long id,
            @Valid @RequestBody CentroMallaRequest req, @RequestParam(required = false) Instant version) {
        return service.guardar(id, req, version);
    }

    /** Últimos cambios guardados en la malla del centro. */
    @GetMapping("/{id}/cambios")
    @PreAuthorize("hasAuthority('VER_MALLA')")
    public Mono<ApiResponse<List<CambioMallaResponse>>> cambios(@PathVariable Long id) {
        return service.cambios(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MODIFICAR_MALLA')")
    public Mono<ApiResponse<Void>> eliminar(@PathVariable Long id) {
        return service.eliminar(id);
    }

    /** Lee un Excel con la plantilla de la malla y devuelve sus datos, sin guardarlos. */
    @PostMapping(value = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MODIFICAR_MALLA')")
    public Mono<ApiResponse<CentroMallaRequest>> importar(@RequestParam("archivo") MultipartFile archivo) {
        return service.importar(archivo);
    }

    @GetMapping("/plantilla")
    @PreAuthorize("hasAuthority('MODIFICAR_MALLA')")
    public Mono<ResponseEntity<byte[]>> plantilla() {
        return service.excel(null).map(bytes -> archivo(bytes, "plantilla-malla.xlsx"));
    }

    @GetMapping("/{id}/excel")
    @PreAuthorize("hasAuthority('EXPORTAR_MALLA')")
    public Mono<ResponseEntity<byte[]>> exportar(@PathVariable Long id) {
        return service.excel(id).map(bytes -> archivo(bytes, "malla-" + id + ".xlsx"));
    }

    private static ResponseEntity<byte[]> archivo(byte[] bytes, String nombre) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nombre).build().toString())
                .body(bytes);
    }
}
