package oracle.picking.controller;

import java.util.Optional;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.response.ApiResponse;
import oracle.picking.response.EstadoPickingResponse;
import oracle.picking.service.ArchivoPicking;
import oracle.picking.service.SincronizadorPicking;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Panel Picking: el navegador descarga el archivo del WMS y calcula los tres
 * módulos con él. La descarga lleva ETag (huella SHA-256): si el archivo no
 * cambió, responde 304 sin volver a enviarlo.
 */
@RestController
@RequestMapping("/api/picking")
public class PickingController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final ArchivoPicking archivo;
    private final SincronizadorPicking sincronizador;

    public PickingController(ArchivoPicking archivo, SincronizadorPicking sincronizador) {
        this.archivo = archivo;
        this.sincronizador = sincronizador;
    }

    @GetMapping("/estado")
    @PreAuthorize("hasAuthority('PANEL_PICKING')")
    public ApiResponse<EstadoPickingResponse> estado() {
        SincronizadorPicking.Estado sync = sincronizador.estado();
        Optional<ArchivoPicking.Info> info = archivo.actual();
        EstadoPickingResponse datos = new EstadoPickingResponse(
                sync.resultado().name(),
                sync.mensaje(),
                sync.revisado(),
                info.isPresent(),
                archivo.nombre(),
                info.map(ArchivoPicking.Info::bytes).orElse(null),
                info.map(ArchivoPicking.Info::modificado).orElse(null),
                info.map(ArchivoPicking.Info::huella).orElse(null));
        return ApiResponse.ok("Estado del archivo de picking.", datos);
    }

    @GetMapping("/archivo")
    @PreAuthorize("hasAuthority('PANEL_PICKING')")
    public ResponseEntity<Resource> archivo(WebRequest request) {
        ArchivoPicking.Info info = archivo.actual()
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Todavía no hay archivo de picking: espera la próxima sincronización con el WMS."));
        if (request.checkNotModified(info.huella(), info.modificado().toEpochMilli())) {
            return null;
        }
        return ResponseEntity.ok()
                .contentType(CSV)
                .eTag(info.huella())
                .lastModified(info.modificado())
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(new FileSystemResource(info.ruta()));
    }
}
