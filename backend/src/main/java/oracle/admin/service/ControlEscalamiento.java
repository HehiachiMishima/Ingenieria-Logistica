package oracle.admin.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import oracle.admin.repository.ResolucionRepository;
import oracle.common.exception.OperacionNoPermitidaException;
import org.springframework.stereotype.Component;

/**
 * Impide la escalada de privilegios dentro de Administracion: nadie puede
 * otorgar un permiso que no tiene, ni administrar (editar, cambiar su clave,
 * desactivar, borrar, cambiarle roles o permisos) a un usuario que tiene
 * permisos que el no tiene. Asi, quien solo administra Bitacora no puede
 * darse acceso a Usuarios agregando permisos a un rol propio, ni tomar la
 * cuenta de un administrador cambiando su contrasena.
 *
 * Los permisos son los efectivos (roles + overrides, deny-wins) leidos dentro
 * de la transaccion en curso. El actor 0 (sistema/seeding) queda exento.
 */
@Component
public class ControlEscalamiento {

    private static final long SISTEMA = 0L;

    private final ResolucionRepository resolucion;

    public ControlEscalamiento(ResolucionRepository resolucion) {
        this.resolucion = resolucion;
    }

    /** El actor debe tener ya cada uno de los permisos que otorga. */
    public void verificarPuedeOtorgar(Long actor, Collection<String> permisos) {
        if (esSistema(actor) || permisos.isEmpty()) {
            return;
        }
        Set<String> faltan = faltantes(actor, permisos);
        if (!faltan.isEmpty()) {
            throw new OperacionNoPermitidaException(
                    "No puede otorgar permisos que usted no tiene: " + String.join(", ", faltan) + ".");
        }
    }

    /** El usuario afectado no puede tener permisos que el actor no tenga. */
    public void verificarPuedeAdministrar(Long actor, Long usuarioId) {
        if (esSistema(actor) || usuarioId == null || usuarioId.equals(actor)) {
            return;
        }
        if (!faltantes(actor, resolucion.nombresPermitidos(usuarioId)).isEmpty()) {
            throw new OperacionNoPermitidaException(
                    "No puede administrar a un usuario que tiene permisos que usted no tiene.");
        }
    }

    private Set<String> faltantes(Long actor, Collection<String> permisos) {
        List<String> propios = resolucion.nombresPermitidos(actor);
        Set<String> faltan = new LinkedHashSet<>();
        for (String permiso : permisos) {
            if (propios.stream().noneMatch(p -> p.equalsIgnoreCase(permiso))) {
                faltan.add(permiso);
            }
        }
        return faltan;
    }

    private static boolean esSistema(Long actor) {
        return actor != null && actor == SISTEMA;
    }
}
