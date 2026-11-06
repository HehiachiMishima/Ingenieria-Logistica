package oracle.bitacora.service;

import oracle.common.config.Transacciones;

import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Listas;
import oracle.common.support.Trazas;

import java.util.List;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.bitacora.model.DataArea;
import oracle.bitacora.model.DataResponsable;
import oracle.bitacora.repository.DataAreaRepository;
import oracle.bitacora.repository.DataResponsableRepository;
import oracle.bitacora.request.AreaActualizarRequest;
import oracle.bitacora.request.AreaCrearRequest;
import oracle.bitacora.request.ResponsableActualizarRequest;
import oracle.bitacora.request.ResponsableCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.bitacora.response.AreaResponse;
import oracle.bitacora.response.ResponsableResponse;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/** CRUD de areas + sub-CRUD de responsables (hijos 1:N). */
@Slf4j
@Service
public class AreaService {

    private final CatalogoBitacora catalogo;
    private final Transacciones transacciones;

    private final DataAreaRepository dataAreaRepository;
    private final DataResponsableRepository dataResponsableRepository;

    public AreaService(DataAreaRepository dataAreaRepository,
            DataResponsableRepository dataResponsableRepository,
            CatalogoBitacora catalogo, Transacciones transacciones) {
        this.catalogo = catalogo;
        this.transacciones = transacciones;
        this.dataAreaRepository = dataAreaRepository;
        this.dataResponsableRepository = dataResponsableRepository;
    }

    public ApiResponse<List<AreaResponse>> listar() {
        List<AreaResponse> datos = Trazas.medir(log, "AreaService.listar", () -> transacciones.consultar(() -> {
            List<AreaResponse> lista = dataAreaRepository.findAll(Listas.limitado()).stream()
                    .map(AreaResponse::of).toList();
            Listas.avisarSiCorto(log, lista.size(), "AreaService.listar");
            return lista;
        }));
        return ApiResponse.ok("Areas listadas.", datos);
    }

    public ApiResponse<AreaResponse> crear(AreaCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "AreaService.crear area=" + peticion.area(), () -> transacciones.ejecutar(() -> {
            if (dataAreaRepository.existsByArea(peticion.area())) {
                throw new ConflictoException("Ya existe un area con el nombre '" + peticion.area() + "'.");
            }

            DataArea area = new DataArea();
            area.setArea(peticion.area().trim());
            Auditoria.alCrear(area, actor);
            dataAreaRepository.save(area);
            catalogo.registrarCambio();

            return ApiResponse.ok("Area creada.", AreaResponse.of(area));
        }));
    }

    public ApiResponse<AreaResponse> actualizar(Long id, AreaActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "AreaService.actualizar id=" + id, () -> transacciones.ejecutar(() -> {
            DataArea area = obtenerArea(id);

            if (peticion.area() != null && !peticion.area().trim().equals(area.getArea())) {
                if (dataAreaRepository.existsByAreaAndIdNot(peticion.area().trim(), id)) {
                    throw new ConflictoException("Ya existe otro area con el nombre '" + peticion.area() + "'.");
                }
                area.setArea(peticion.area().trim());
            }

            Auditoria.alActualizar(area, actor);
            dataAreaRepository.save(area);
            catalogo.registrarCambio();
            return ApiResponse.ok("Area actualizada.", AreaResponse.of(area));
        }));
    }

    public ApiResponse<Void> eliminar(Long id) {
        Trazas.medir(log, "AreaService.eliminar id=" + id, () -> transacciones.ejecutar(() -> {
            DataArea area = obtenerArea(id);
            long responsables = dataResponsableRepository.countByIdDataArea(id);
            long incidencias = dataAreaRepository.contarIncidencias(id);
            if (responsables > 0 || incidencias > 0) {
                throw new ConflictoException(
                        "No se puede eliminar el area '" + area.getArea() + "': tiene "
                                + responsables + " responsable(s) e "
                                + incidencias + " incidencia(s) registrada(s).");
            }
            dataAreaRepository.delete(area);
            catalogo.registrarCambio();
            return null;
        }));
        return ApiResponse.ok("Area eliminada.", null);
    }

    public ApiResponse<List<ResponsableResponse>> responsablesDe(Long areaId) {
        List<ResponsableResponse> datos = Trazas.medir(log, "AreaService.responsablesDe areaId=" + areaId, () -> transacciones.consultar(() -> {
            obtenerArea(areaId);
            return dataResponsableRepository.findByIdDataAreaOrderById(areaId).stream()
                    .map(ResponsableResponse::of).toList();
        }));
        return ApiResponse.ok("Responsables del area.", datos);
    }

    public ApiResponse<ResponsableResponse> crearResponsable(Long areaId, ResponsableCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log,
                "AreaService.crearResponsable areaId=" + areaId + " responsable=" + peticion.responsable(), () -> transacciones.ejecutar(() -> {
                    obtenerArea(areaId);
                    if (dataResponsableRepository.existsByResponsable(peticion.responsable())) {
                        throw new ConflictoException(
                                "Ya existe un responsable con el nombre '" + peticion.responsable()
                                        + "' (puede pertenecer a otra area).");
                    }

                    DataResponsable responsable = new DataResponsable();
                    responsable.setIdDataArea(areaId);
                    responsable.setResponsable(peticion.responsable().trim());
                    Auditoria.alCrear(responsable, actor);
                    dataResponsableRepository.save(responsable);
                    catalogo.registrarCambio();

                    return ApiResponse.ok("Responsable creado.", ResponsableResponse.of(responsable));
                }));
    }

    public ApiResponse<ResponsableResponse> actualizarResponsable(Long areaId, Long responsableId,
            ResponsableActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "AreaService.actualizarResponsable id=" + responsableId, () -> transacciones.ejecutar(() -> {
            DataResponsable responsable = obtenerResponsable(areaId, responsableId);

            if (peticion.responsable() != null && !peticion.responsable().trim().equals(responsable.getResponsable())) {
                if (dataResponsableRepository.existsByResponsableAndIdNot(peticion.responsable().trim(), responsableId)) {
                    throw new ConflictoException(
                            "Ya existe otro responsable con el nombre '" + peticion.responsable()
                                    + "' (puede pertenecer a otra area).");
                }
                responsable.setResponsable(peticion.responsable().trim());
            }

            Auditoria.alActualizar(responsable, actor);
            dataResponsableRepository.save(responsable);
            catalogo.registrarCambio();
            return ApiResponse.ok("Responsable actualizado.", ResponsableResponse.of(responsable));
        }));
    }

    public ApiResponse<Void> eliminarResponsable(Long areaId, Long responsableId) {
        Trazas.medir(log,
                "AreaService.eliminarResponsable id=" + responsableId, () -> transacciones.ejecutar(() -> {
                    DataResponsable responsable = obtenerResponsable(areaId, responsableId);
                    long incidencias = dataResponsableRepository.contarIncidencias(responsableId);
                    if (incidencias > 0) {
                        throw new ConflictoException(
                                "No se puede eliminar el responsable '" + responsable.getResponsable() + "': tiene "
                                        + incidencias + " incidencia(s) registrada(s).");
                    }
                    dataResponsableRepository.delete(responsable);
                    catalogo.registrarCambio();
                    return null;
                }));
        return ApiResponse.ok("Responsable eliminado.", null);
    }

    private DataArea obtenerArea(Long id) {
        return dataAreaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el area con id " + id + "."));
    }

    private DataResponsable obtenerResponsable(Long areaId, Long responsableId) {
        DataResponsable responsable = dataResponsableRepository.findById(responsableId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe el responsable con id " + responsableId + "."));
        if (!areaId.equals(responsable.getIdDataArea())) {
            throw new RecursoNoEncontradoException(
                    "El responsable con id " + responsableId + " no pertenece al area con id " + areaId + ".");
        }
        return responsable;
    }
}
