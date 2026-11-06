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
import oracle.admin.model.Rol;
import oracle.admin.repository.RolRepository;
import oracle.admin.request.CambiarEstadoRequest;
import oracle.admin.request.RolActualizarRequest;
import oracle.admin.request.RolCrearRequest;
import oracle.common.response.ApiResponse;
import oracle.admin.response.RolResponse;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/** CRUD de roles con filtros LIKE y protecciones del trigger de ADMINISTRADOR. */
@Slf4j
@Service
public class RolService {

    private final AutorizacionCache autorizacionCache;
    private final RolRepository rolRepository;
    private final Transacciones transacciones;

    public RolService(RolRepository rolRepository, AutorizacionCache autorizacionCache,
            Transacciones transacciones) {
        this.autorizacionCache = autorizacionCache;
        this.rolRepository = rolRepository;
        this.transacciones = transacciones;
    }

    public ApiResponse<List<RolResponse>> listar() {
        List<RolResponse> datos = Trazas.medir(log, "RolService.listar", () -> transacciones.consultar(() -> {
            List<RolResponse> lista = rolRepository.findAll(Listas.limitado()).stream()
                    .map(RolResponse::of).toList();
            Listas.avisarSiCorto(log, lista.size(), "RolService.listar");
            return lista;
        }));
        return ApiResponse.ok("Roles listados.", datos);
    }

    public ApiResponse<RolResponse> crear(RolCrearRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "RolService.crear nombre=" + peticion.nombre(), () -> transacciones.ejecutar(() -> {
            if (rolRepository.existsByNombre(peticion.nombre())) {
                throw new ConflictoException("Ya existe un rol con el nombre '" + peticion.nombre() + "'.");
            }

            Rol rol = new Rol();
            rol.setNombre(peticion.nombre().trim());
            rol.setDescripcion(peticion.descripcion());
            rol.setActivo(peticion.activo() == null ? Boolean.TRUE : peticion.activo());
            Auditoria.alCrear(rol, actor);
            rolRepository.save(rol);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);

            return ApiResponse.ok("Rol creado.", RolResponse.of(rol));
        }));
    }

    public ApiResponse<RolResponse> actualizar(Long id, RolActualizarRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "RolService.actualizar id=" + id, () -> transacciones.ejecutar(() -> {
            Rol rol = obtenerRol(id);
            verificarNoAdministrador(rol);

            if (peticion.nombre() != null && !peticion.nombre().trim().equals(rol.getNombre())) {
                if (rolRepository.existsByNombreAndIdNot(peticion.nombre().trim(), id)) {
                    throw new ConflictoException("Ya existe otro rol con el nombre '" + peticion.nombre() + "'.");
                }
                rol.setNombre(peticion.nombre().trim());
            }
            if (peticion.descripcion() != null) {
                rol.setDescripcion(peticion.descripcion());
            }
            if (peticion.activo() != null) {
                rol.setActivo(peticion.activo());
            }

            Auditoria.alActualizar(rol, actor);
            rolRepository.save(rol);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);
            return ApiResponse.ok("Rol actualizado.", RolResponse.of(rol));
        }));
    }

    public ApiResponse<Void> eliminar(Long id) {
        Trazas.medir(log, "RolService.eliminar id=" + id, () -> transacciones.ejecutar(() -> {
            Rol rol = obtenerRol(id);
            verificarNoAdministrador(rol);
            rolRepository.delete(rol);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);
            return null;
        }));
        return ApiResponse.ok("Rol eliminado.", null);
    }

    public ApiResponse<RolResponse> cambiarActivo(Long id, CambiarEstadoRequest peticion) {
        Long actor = ActorActual.id();
        return Trazas.medir(log, "RolService.cambiarActivo id=" + id, () -> transacciones.ejecutar(() -> {
            Rol rol = obtenerRol(id);
            verificarNoAdministrador(rol);
            rol.setActivo(peticion.activo());
            Auditoria.alActualizar(rol, actor);
            rolRepository.save(rol);
            transacciones.alConfirmar(autorizacionCache::invalidarTodo);

            String mensaje = peticion.activo() ? "Rol activado." : "Rol desactivado.";
            return ApiResponse.ok(mensaje, RolResponse.of(rol));
        }));
    }

    private Rol obtenerRol(Long id) {
        return rolRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el rol con id " + id + "."));
    }

    private void verificarNoAdministrador(Rol rol) {
        if (Protecciones.esRolAdministrador(rol.getNombre())) {
            throw new OperacionNoPermitidaException(
                    "No se puede modificar ni eliminar el rol ADMINISTRADOR. Solo se le pueden agregar permisos.");
        }
    }
}
