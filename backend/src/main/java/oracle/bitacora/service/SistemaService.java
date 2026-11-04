package oracle.bitacora.service;

import oracle.common.config.Transacciones;

import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Listas;
import oracle.common.support.Trazas;

import java.util.List;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.bitacora.model.DataIncidencia;
import oracle.bitacora.model.DataSistema;
import oracle.bitacora.repository.DataIncidenciaRepository;
import oracle.bitacora.repository.DataSistemaRepository;
import oracle.bitacora.request.SistemaActualizarRequest;
import oracle.bitacora.request.SistemaCrearRequest;
import oracle.bitacora.request.TipoIncidenciaActualizarRequest;
import oracle.bitacora.request.TipoIncidenciaCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.bitacora.response.SistemaResponse;
import oracle.bitacora.response.TipoIncidenciaResponse;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** CRUD de sistemas + sub-CRUD de tipos de incidencia (hijos 1:N). */
@Slf4j
@Service
public class SistemaService {

    private final CatalogoBitacora catalogo;
    private final Transacciones transacciones;

    private final DataSistemaRepository dataSistemaRepository;
    private final DataIncidenciaRepository dataIncidenciaRepository;

    public SistemaService(DataSistemaRepository dataSistemaRepository,
            DataIncidenciaRepository dataIncidenciaRepository,
            CatalogoBitacora catalogo, Transacciones transacciones) {
        this.catalogo = catalogo;
        this.transacciones = transacciones;
        this.dataSistemaRepository = dataSistemaRepository;
        this.dataIncidenciaRepository = dataIncidenciaRepository;
    }

    public Mono<ApiResponse<List<SistemaResponse>>> listar() {
        return Mono.fromCallable(() -> Trazas.medir(log, "SistemaService.listar", () -> transacciones.consultar(() -> {
                        List<SistemaResponse> lista = dataSistemaRepository.findAll(Listas.limitado()).stream()
                                .map(SistemaResponse::of).toList();
                        Listas.avisarSiCorto(log, lista.size(), "SistemaService.listar");
                        return lista;
                    })))
                .subscribeOn(Schedulers.boundedElastic())
                .map(lista -> ApiResponse.ok("Sistemas listados.", lista));
    }

    public Mono<ApiResponse<SistemaResponse>> crear(SistemaCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Mono.fromCallable(() -> Trazas.medir(log, "SistemaService.crear sistema=" + peticion.sistema(), () -> transacciones.ejecutar(() -> {
            if (dataSistemaRepository.existsBySistema(peticion.sistema())) {
                throw new ConflictoException("Ya existe un sistema con el nombre '" + peticion.sistema() + "'.");
            }

            DataSistema sistema = new DataSistema();
            sistema.setSistema(peticion.sistema().trim());
            Auditoria.alCrear(sistema, actor);
            dataSistemaRepository.save(sistema);
            catalogo.registrarCambio();

            return ApiResponse.ok("Sistema creado.", SistemaResponse.of(sistema));
        }))).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ApiResponse<SistemaResponse>> actualizar(Long id, SistemaActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Mono.fromCallable(() -> Trazas.medir(log, "SistemaService.actualizar id=" + id, () -> transacciones.ejecutar(() -> {
            DataSistema sistema = obtenerSistema(id);

            if (peticion.sistema() != null && !peticion.sistema().trim().equals(sistema.getSistema())) {
                if (dataSistemaRepository.existsBySistemaAndIdNot(peticion.sistema().trim(), id)) {
                    throw new ConflictoException("Ya existe otro sistema con el nombre '" + peticion.sistema() + "'.");
                }
                sistema.setSistema(peticion.sistema().trim());
            }

            Auditoria.alActualizar(sistema, actor);
            dataSistemaRepository.save(sistema);
            catalogo.registrarCambio();
            return ApiResponse.ok("Sistema actualizado.", SistemaResponse.of(sistema));
        }))).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ApiResponse<Void>> eliminar(Long id) {
        return Mono.<Void>fromRunnable(() -> Trazas.medir(log, "SistemaService.eliminar id=" + id, () -> transacciones.ejecutar(() -> {
                    DataSistema sistema = obtenerSistema(id);
                    long tipos = dataIncidenciaRepository.countByIdDataSistema(id);
                    long incidencias = dataSistemaRepository.contarIncidencias(id);
                    if (tipos > 0 || incidencias > 0) {
                        throw new ConflictoException(
                                "No se puede eliminar el sistema '" + sistema.getSistema() + "': tiene "
                                        + tipos + " tipo(s) de incidencia y "
                                        + incidencias + " incidencia(s) registrada(s).");
                    }
                    dataSistemaRepository.delete(sistema);
                    catalogo.registrarCambio();
                    return null;
                })))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.fromSupplier(() -> ApiResponse.<Void>ok("Sistema eliminado.", null)));
    }

    public Mono<ApiResponse<List<TipoIncidenciaResponse>>> tiposDe(Long sistemaId) {
        return Mono.fromCallable(() -> Trazas.medir(log, "SistemaService.tiposDe sistemaId=" + sistemaId, () -> transacciones.consultar(() -> {
                    obtenerSistema(sistemaId);
                    return dataIncidenciaRepository.findByIdDataSistemaOrderById(sistemaId).stream()
                            .map(TipoIncidenciaResponse::of).toList();
                })))
                .subscribeOn(Schedulers.boundedElastic())
                .map(lista -> ApiResponse.ok("Tipos de incidencia del sistema.", lista));
    }

    public Mono<ApiResponse<TipoIncidenciaResponse>> crearTipo(Long sistemaId, TipoIncidenciaCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Mono.fromCallable(() -> Trazas.medir(log,
                "SistemaService.crearTipo sistemaId=" + sistemaId + " incidencia=" + peticion.incidencia(), () -> transacciones.ejecutar(() -> {
                    obtenerSistema(sistemaId);
                    if (dataIncidenciaRepository.existsByIdDataSistemaAndIncidencia(sistemaId, peticion.incidencia())) {
                        throw new ConflictoException(
                                "Ya existe el tipo de incidencia '" + peticion.incidencia() + "' para este sistema.");
                    }

                    DataIncidencia tipo = new DataIncidencia();
                    tipo.setIdDataSistema(sistemaId);
                    tipo.setIncidencia(peticion.incidencia().trim());
                    Auditoria.alCrear(tipo, actor);
                    dataIncidenciaRepository.save(tipo);
                    catalogo.registrarCambio();

                    return ApiResponse.ok("Tipo de incidencia creado.", TipoIncidenciaResponse.of(tipo));
                }))).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ApiResponse<TipoIncidenciaResponse>> actualizarTipo(Long sistemaId, Long tipoId,
            TipoIncidenciaActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Mono.fromCallable(() -> Trazas.medir(log, "SistemaService.actualizarTipo tipoId=" + tipoId, () -> transacciones.ejecutar(() -> {
            DataIncidencia tipo = obtenerTipo(sistemaId, tipoId);

            if (peticion.incidencia() != null && !peticion.incidencia().trim().equals(tipo.getIncidencia())) {
                if (dataIncidenciaRepository.existsByIdDataSistemaAndIncidenciaAndIdNot(
                        sistemaId, peticion.incidencia().trim(), tipoId)) {
                    throw new ConflictoException(
                            "Ya existe otro tipo de incidencia '" + peticion.incidencia() + "' para este sistema.");
                }
                tipo.setIncidencia(peticion.incidencia().trim());
            }

            Auditoria.alActualizar(tipo, actor);
            dataIncidenciaRepository.save(tipo);
            catalogo.registrarCambio();
            return ApiResponse.ok("Tipo de incidencia actualizado.", TipoIncidenciaResponse.of(tipo));
        }))).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ApiResponse<Void>> eliminarTipo(Long sistemaId, Long tipoId) {
        return Mono.<Void>fromRunnable(() -> Trazas.medir(log, "SistemaService.eliminarTipo tipoId=" + tipoId, () -> transacciones.ejecutar(() -> {
                    DataIncidencia tipo = obtenerTipo(sistemaId, tipoId);
                    long incidencias = dataIncidenciaRepository.contarIncidencias(tipoId);
                    if (incidencias > 0) {
                        throw new ConflictoException(
                                "No se puede eliminar el tipo de incidencia '" + tipo.getIncidencia() + "': tiene "
                                        + incidencias + " incidencia(s) registrada(s).");
                    }
                    dataIncidenciaRepository.delete(tipo);
                    catalogo.registrarCambio();
                    return null;
                })))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.fromSupplier(() -> ApiResponse.<Void>ok("Tipo de incidencia eliminado.", null)));
    }

    private DataSistema obtenerSistema(Long id) {
        return dataSistemaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el sistema con id " + id + "."));
    }

    private DataIncidencia obtenerTipo(Long sistemaId, Long tipoId) {
        DataIncidencia tipo = dataIncidenciaRepository.findById(tipoId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe el tipo de incidencia con id " + tipoId + "."));
        if (!sistemaId.equals(tipo.getIdDataSistema())) {
            throw new RecursoNoEncontradoException(
                    "El tipo de incidencia con id " + tipoId + " no pertenece al sistema con id " + sistemaId + ".");
        }
        return tipo;
    }
}
