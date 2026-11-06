package oracle.admin.service;

import oracle.common.config.Transacciones;
import oracle.common.security.AutorizacionCache;
import oracle.common.support.ActorActual;
import oracle.common.support.Auditoria;
import oracle.common.support.Listas;
import oracle.common.support.Trazas;

import java.util.List;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.admin.model.Permiso;
import oracle.admin.repository.PermisoRepository;
import oracle.admin.request.CambiarEstadoRequest;
import oracle.admin.request.PermisoActualizarRequest;
import oracle.admin.request.PermisoCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.PermisoResponse;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD de permisos. Los permisos base (Protecciones.PERMISOS_BASE) estan
 * blindados: no se pueden modificar ni eliminar, exactamente igual que los
 * triggers trg_permisos_base_update / trg_permisos_base_delete de la BD.
 * Se valida aqui para responder 403 limpio antes de que el trigger de PostgreSQL
 * falle con SQLSTATE AT403.
 */
@Slf4j
@Service
public class PermisoService {

    private final AutorizacionCache autorizacionCache;
    private final PermisoRepository permisoRepository;
    private final Transacciones transacciones;

    public PermisoService(PermisoRepository permisoRepository, AutorizacionCache autorizacionCache,
            Transacciones transacciones) {
        this.autorizacionCache = autorizacionCache;
        this.permisoRepository = permisoRepository;
        this.transacciones = transacciones;
    }

    public ApiResponse<List<PermisoResponse>> listar() {
        List<PermisoResponse> datos = Trazas.medir(log, "PermisoService.listar", () -> transacciones.consultar(() -> {
            List<PermisoResponse> lista = permisoRepository.findAll(Listas.limitado()).stream()
                    .map(PermisoResponse::of).toList();
            Listas.avisarSiCorto(log, lista.size(), "PermisoService.listar");
            return lista;
        }));
        return ApiResponse.ok("Permisos listados.", datos);
    }

    public ApiResponse<PermisoResponse> crear(PermisoCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "PermisoService.crear nombre=" + peticion.nombre(), () -> transacciones.ejecutar(() -> {
            if (permisoRepository.existsByNombre(peticion.nombre())) {
                throw new ConflictoException("Ya existe un permiso con el nombre '" + peticion.nombre() + "'.");
            }

            Permiso permiso = new Permiso();
            permiso.setNombre(peticion.nombre().trim());
            permiso.setDescripcion(peticion.descripcion());
            permiso.setActivo(peticion.activo() == null ? Boolean.TRUE : peticion.activo());
            Auditoria.alCrear(permiso, actor);
            permisoRepository.save(permiso);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);

            return ApiResponse.ok("Permiso creado.", PermisoResponse.of(permiso));
        }));
    }

    public ApiResponse<PermisoResponse> actualizar(Long id, PermisoActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "PermisoService.actualizar id=" + id, () -> transacciones.ejecutar(() -> {
            Permiso permiso = obtenerPermiso(id);
            boolean esBase = Protecciones.esPermisoBase(permiso.getNombre());

            boolean cambiaNombre = peticion.nombre() != null
                    && !peticion.nombre().trim().equals(permiso.getNombre());
            boolean cambiaDescripcion = peticion.descripcion() != null
                    && !peticion.descripcion().equals(permiso.getDescripcion());
            boolean cambiaActivo = peticion.activo() != null
                    && !peticion.activo().equals(permiso.getActivo());
            if (esBase && (cambiaNombre || cambiaDescripcion || cambiaActivo)) {
                throw new OperacionNoPermitidaException(
                        "No se puede modificar un permiso base. Es inmutable (nombre, descripcion y estado).");
            }

            if (cambiaNombre) {
                if (permisoRepository.existsByNombreAndIdNot(peticion.nombre().trim(), id)) {
                    throw new ConflictoException("Ya existe otro permiso con el nombre '" + peticion.nombre() + "'.");
                }
                permiso.setNombre(peticion.nombre().trim());
            }
            if (cambiaDescripcion) {
                permiso.setDescripcion(peticion.descripcion());
            }
            if (peticion.activo() != null) {
                permiso.setActivo(peticion.activo());
            }

            Auditoria.alActualizar(permiso, actor);
            permisoRepository.save(permiso);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);
            return ApiResponse.ok("Permiso actualizado.", PermisoResponse.of(permiso));
        }));
    }

    public ApiResponse<Void> eliminar(Long id) {
        Trazas.medir(log, "PermisoService.eliminar id=" + id, () -> transacciones.ejecutar(() -> {
            Permiso permiso = obtenerPermiso(id);
            if (Protecciones.esPermisoBase(permiso.getNombre())) {
                throw new OperacionNoPermitidaException(
                        "No se puede eliminar un permiso base. Es inmutable e intocable.");
            }
            permisoRepository.delete(permiso);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);
            return null;
        }));
        return ApiResponse.ok("Permiso eliminado.", null);
    }

    public ApiResponse<PermisoResponse> cambiarActivo(Long id, CambiarEstadoRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "PermisoService.cambiarActivo id=" + id, () -> transacciones.ejecutar(() -> {
            Permiso permiso = obtenerPermiso(id);
            if (Protecciones.esPermisoBase(permiso.getNombre())) {
                throw new OperacionNoPermitidaException(
                        "No se puede activar ni desactivar un permiso base. Es inmutable.");
            }
            permiso.setActivo(peticion.activo());
            Auditoria.alActualizar(permiso, actor);
            permisoRepository.save(permiso);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);

            String mensaje = peticion.activo() ? "Permiso activado." : "Permiso desactivado.";
            return ApiResponse.ok(mensaje, PermisoResponse.of(permiso));
        }));
    }

    private Permiso obtenerPermiso(Long id) {
        return permisoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el permiso con id " + id + "."));
    }
}
