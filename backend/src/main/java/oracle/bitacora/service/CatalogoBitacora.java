package oracle.bitacora.service;

import java.util.Map;
import java.util.stream.Collectors;
import oracle.bitacora.model.DataArea;
import oracle.bitacora.model.DataCentro;
import oracle.bitacora.model.DataIncidencia;
import oracle.bitacora.model.DataResponsable;
import oracle.bitacora.model.DataSistema;
import oracle.bitacora.repository.DataAreaRepository;
import oracle.bitacora.repository.DataCentroRepository;
import oracle.bitacora.repository.DataIncidenciaRepository;
import oracle.bitacora.repository.DataResponsableRepository;
import oracle.bitacora.repository.DataSistemaRepository;
import oracle.common.config.CacheRespuestas;
import oracle.common.config.Transacciones;
import oracle.common.support.CatalogoCacheado;
import org.springframework.stereotype.Component;

/**
 * Cache de nombres de los catalogos del panel Bitacora (centros, sistemas,
 * tipos, areas, responsables) para resolver una pagina de incidencias sin
 * una consulta por fila. Los services de Control registran cada CRUD con
 * registrarCambio().
 */
@Component
public class CatalogoBitacora extends CatalogoCacheado<CatalogoBitacora.Snapshot> {

    record Snapshot(
            Map<Long, String> centros,
            Map<Long, String> sistemas,
            Map<Long, String> tipos,
            Map<Long, String> areas,
            Map<Long, String> responsables) {}

    public CatalogoBitacora(
            DataCentroRepository centroRepo,
            DataSistemaRepository sistemaRepo,
            DataIncidenciaRepository tipoRepo,
            DataAreaRepository areaRepo,
            DataResponsableRepository responsableRepo,
            Transacciones transacciones,
            CacheRespuestas cacheRespuestas) {
        super("catalogo-bitacora", () -> new Snapshot(
                centroRepo.findAll().stream()
                        .collect(Collectors.toUnmodifiableMap(DataCentro::getId, DataCentro::getCentro, (a, b) -> a)),
                sistemaRepo.findAll().stream()
                        .collect(Collectors.toUnmodifiableMap(DataSistema::getId, DataSistema::getSistema, (a, b) -> a)),
                tipoRepo.findAll().stream()
                        .collect(Collectors.toUnmodifiableMap(DataIncidencia::getId, DataIncidencia::getIncidencia, (a, b) -> a)),
                areaRepo.findAll().stream()
                        .collect(Collectors.toUnmodifiableMap(DataArea::getId, DataArea::getArea, (a, b) -> a)),
                responsableRepo.findAll().stream()
                        .collect(Collectors.toUnmodifiableMap(DataResponsable::getId,
                                r -> r.getResponsable() != null ? r.getResponsable() : "", (a, b) -> a))),
                transacciones, cacheRespuestas, IncidenciaService.ESPACIO_CACHE);
    }

    public Map<Long, String> centros() { return snapshot().centros(); }
    public Map<Long, String> sistemas() { return snapshot().sistemas(); }
    public Map<Long, String> tipos() { return snapshot().tipos(); }
    public Map<Long, String> areas() { return snapshot().areas(); }
    public Map<Long, String> responsables() { return snapshot().responsables(); }
}
