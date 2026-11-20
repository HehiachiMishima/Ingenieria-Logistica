package oracle.admin.service;

import oracle.common.support.ActorActual;
import oracle.common.support.Trazas;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import oracle.common.exception.ConflictoException;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.admin.model.Permiso;
import oracle.admin.model.Rol;
import oracle.admin.model.RolPermiso;
import oracle.admin.repository.PermisoRepository;
import oracle.admin.repository.RolPermisoLoteRepository;
import oracle.admin.repository.RolPermisoRepository;
import oracle.admin.repository.RolRepository;
import oracle.admin.request.RolPermisoAsignarRequest;
import oracle.admin.request.RolPermisosLoteRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.PermisoResponse;
import oracle.admin.response.PermisoTableroResponse;
import oracle.admin.response.RolPermisoResponse;
import oracle.admin.response.RolPermisosLoteResponse;
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
    private final RolPermisoLoteRepository lote;
    private final AutorizacionCache autorizacionCache;
    private final Transacciones transacciones;
    private final ControlEscalamiento escalamiento;

    public RolPermisoService(
            RolRepository rolRepository,
            PermisoRepository permisoRepository,
            RolPermisoRepository rolPermisoRepository,
            RolPermisoLoteRepository lote,
            AutorizacionCache autorizacionCache,
            Transacciones transacciones,
            ControlEscalamiento escalamiento) {
        this.rolRepository = rolRepository;
        this.permisoRepository = permisoRepository;
        this.rolPermisoRepository = rolPermisoRepository;
        this.lote = lote;
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

    /** Catálogo completo visto desde el rol: asignado o no y en cuántos roles está cada permiso. */
    public ApiResponse<List<PermisoTableroResponse>> tablero(Long rolId) {
        List<PermisoTableroResponse> datos = Trazas.medir(log, "RolPermisoService.tablero rolId=" + rolId,
                () -> transacciones.consultar(() -> {
                    if (!rolRepository.existsById(rolId)) {
                        throw new RecursoNoEncontradoException("No existe el rol con id " + rolId + ".");
                    }
                    return lote.tablero(rolId);
                }));
        return ApiResponse.ok("Tablero de permisos del rol.", datos);
    }

    /**
     * Agrega y quita varios permisos del rol en una sola transacción: o se aplican
     * todos o ninguno. Lo que el rol ya tenía (o ya no tenía) se ignora, así un
     * reintento no falla. Mismas reglas que uno a uno: no se otorga lo que el actor
     * no tiene y al rol ADMINISTRADOR no se le quitan permisos.
     */
    public ApiResponse<RolPermisosLoteResponse> actualizarLote(Long rolId, RolPermisosLoteRequest peticion) {
        Long actor = ActorActual.id();
        Set<Long> agregar = new LinkedHashSet<>(peticion.agregar());
        Set<Long> quitar = new LinkedHashSet<>(peticion.quitar());
        if (agregar.isEmpty() && quitar.isEmpty()) {
            throw new SolicitudInvalidaException("Indique al menos un permiso para agregar o quitar.");
        }
        if (agregar.stream().anyMatch(quitar::contains)) {
            throw new SolicitudInvalidaException("Un mismo permiso no puede agregarse y quitarse a la vez.");
        }
        return Trazas.medir(log,
                "RolPermisoService.actualizarLote rolId=" + rolId + " agregar=" + agregar.size() + " quitar=" + quitar.size(),
                () -> transacciones.ejecutar(() -> {
                    Rol rol = obtenerRol(rolId);
                    if (!quitar.isEmpty() && Protecciones.esRolAdministrador(rol.getNombre())) {
                        throw new OperacionNoPermitidaException(
                                "No se pueden quitar permisos al rol ADMINISTRADOR. Solo se pueden agregar.");
                    }
                    Set<Long> pedidos = new LinkedHashSet<>(agregar);
                    pedidos.addAll(quitar);
                    Map<Long, String> nombres = lote.nombres(pedidos);
                    List<Long> inexistentes = pedidos.stream().filter(id -> !nombres.containsKey(id)).toList();
                    if (!inexistentes.isEmpty()) {
                        throw new RecursoNoEncontradoException("No existen los permisos con id "
                                + inexistentes.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ".");
                    }

                    Set<Long> actuales = new LinkedHashSet<>(lote.asignados(rolId));
                    List<Long> nuevos = agregar.stream().filter(id -> !actuales.contains(id)).toList();
                    List<Long> retirados = quitar.stream().filter(actuales::contains).toList();
                    escalamiento.verificarPuedeOtorgar(actor, nuevos.stream().map(nombres::get).toList());

                    int agregados = lote.agregar(rolId, nuevos, actor);
                    int quitados = lote.quitar(rolId, retirados);
                    if (agregados + quitados > 0) {
                        transacciones.alConfirmar(autorizacionCache::invalidarTodo);
                    }
                    return ApiResponse.ok("Permisos del rol actualizados.",
                            new RolPermisosLoteResponse(rolId, agregados, quitados, lote.asignados(rolId)));
                }));
    }

    private Rol obtenerRol(Long id) {
        return rolRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el rol con id " + id + "."));
    }
}
