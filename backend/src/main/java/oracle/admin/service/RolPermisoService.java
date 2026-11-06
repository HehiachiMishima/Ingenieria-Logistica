package oracle.admin.service;

import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;

import java.time.Instant;
import java.util.List;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.admin.model.Permiso;
import oracle.admin.model.Rol;
import oracle.admin.model.RolPermiso;
import oracle.admin.repository.PermisoRepository;
import oracle.admin.repository.RolPermisoRepository;
import oracle.admin.repository.RolRepository;
import oracle.admin.request.RolPermisoAsignarRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.PermisoResponse;
import oracle.admin.response.RolPermisoResponse;
import oracle.admin.response.RolResponse;
import oracle.common.security.AutorizacionCache;
import oracle.common.config.Transacciones;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * Plantilla RBAC rol-permiso (roles_permisos). ADMINISTRADOR solo recibe
 * permisos: quitarlos queda bloqueado, igual que el trigger de la base.
 */
@Slf4j
@Service
public class RolPermisoService {

    private final RolRepository rolRepository;
    private final PermisoRepository permisoRepository;
    private final RolPermisoRepository rolPermisoRepository;
    private final AutorizacionCache autorizacionCache;
    private final Transacciones transacciones;
    private final ControlEscalamiento escalamiento;

    public RolPermisoService(
            RolRepository rolRepository,
            PermisoRepository permisoRepository,
            RolPermisoRepository rolPermisoRepository,
            AutorizacionCache autorizacionCache,
            Transacciones transacciones,
            ControlEscalamiento escalamiento) {
        this.rolRepository = rolRepository;
        this.permisoRepository = permisoRepository;
        this.rolPermisoRepository = rolPermisoRepository;
        this.autorizacionCache = autorizacionCache;
        this.transacciones = transacciones;
        this.escalamiento = escalamiento;
    }

    /** Agregar un permiso al rol (unico por par). */
    public ApiResponse<RolPermisoResponse> asignar(Long rolId, RolPermisoAsignarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log,
                "RolPermisoService.asignar rolId=" + rolId + " permisoId=" + peticion.permisoId(),
                () -> transacciones.ejecutar(() -> {
                    Rol rol = obtenerRol(rolId);
                    Permiso permiso = permisoRepository.findById(peticion.permisoId())
                            .orElseThrow(() -> new RecursoNoEncontradoException(
                                    "No existe el permiso con id " + peticion.permisoId() + "."));

                    escalamiento.verificarPuedeOtorgar(actor, List.of(permiso.getNombre()));
                    if (rolPermisoRepository.existsByRolIdAndPermisoId(rolId, peticion.permisoId())) {
                        throw new ConflictoException("El rol '" + rol.getNombre()
                                + "' ya tiene el permiso '" + permiso.getNombre() + "'.");
                    }

                    RolPermiso plantilla = new RolPermiso();
                    plantilla.setRol(rol);
                    plantilla.setPermiso(permiso);
                    plantilla.setCreadoPor(actor);
                    plantilla.setCreadoEn(Instant.now());
                    rolPermisoRepository.save(plantilla);
                    transacciones.alConfirmar(autorizacionCache::invalidarTodo);

                    return ApiResponse.ok("Permiso agregado al rol.", RolPermisoResponse.of(plantilla));
                }));
    }

    /** Quitar un permiso del rol (bloqueado para ADMINISTRADOR). */
    public ApiResponse<Void> quitar(Long rolId, Long permisoId) {
        Trazas.medir(log,
                "RolPermisoService.quitar rolId=" + rolId + " permisoId=" + permisoId,
                () -> transacciones.ejecutar(() -> {
                    Rol rol = obtenerRol(rolId);
                    if (Protecciones.esRolAdministrador(rol.getNombre())) {
                        throw new OperacionNoPermitidaException(
                                "No se pueden quitar permisos al rol ADMINISTRADOR. Solo se pueden agregar.");
                    }
                    if (!rolPermisoRepository.existsByRolIdAndPermisoId(rolId, permisoId)) {
                        throw new RecursoNoEncontradoException("El rol '" + rol.getNombre()
                                + "' no tiene el permiso con id " + permisoId + ".");
                    }
                    rolPermisoRepository.eliminarPar(rolId, permisoId);
                    transacciones.alConfirmar(autorizacionCache::invalidarTodo);
                    return null;
                }));
        return ApiResponse.ok("Permiso quitado del rol.", null);
    }

    /** Permisos de un rol. */
    public ApiResponse<List<PermisoResponse>> listarPorRol(Long rolId) {
        List<PermisoResponse> datos = Trazas.medir(log, "RolPermisoService.listarPorRol rolId=" + rolId, () -> transacciones.consultar(() -> {
            if (!rolRepository.existsById(rolId)) {
                throw new RecursoNoEncontradoException("No existe el rol con id " + rolId + ".");
            }
            return rolPermisoRepository.permisosDeRol(rolId).stream().map(PermisoResponse::of).toList();
        }));
        return ApiResponse.ok("Permisos del rol.", datos);
    }

    /** Roles que tienen un permiso. */
    public ApiResponse<List<RolResponse>> listarPorPermiso(Long permisoId) {
        List<RolResponse> datos = Trazas.medir(log, "RolPermisoService.listarPorPermiso permisoId=" + permisoId, () -> transacciones.consultar(() -> {
            if (!permisoRepository.existsById(permisoId)) {
                throw new RecursoNoEncontradoException("No existe el permiso con id " + permisoId + ".");
            }
            return rolPermisoRepository.rolesConPermiso(permisoId).stream().map(RolResponse::of).toList();
        }));
        return ApiResponse.ok("Roles con el permiso.", datos);
    }

    private Rol obtenerRol(Long id) {
        return rolRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el rol con id " + id + "."));
    }
}
