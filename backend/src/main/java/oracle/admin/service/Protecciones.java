package oracle.admin.service;

import java.util.Set;
import oracle.common.exception.OperacionNoPermitidaException;

/**
 * Reglas de proteccion espejo de los triggers de la base de datos.
 * Validar aqui = fallar con 403 limpio antes de que el trigger de PostgreSQL
 * falle con SQLSTATE AT403.
 */
final class Protecciones {

    /**
     * Permisos base: inmutables. No se pueden modificar (ni siquiera su campo
     * 'activo') ni eliminar. Espejo de trg_permisos_base_update / _delete.
     * Son los 14 de gestion + PANEL_ADMIN + DASHBOARD_ADMIN.
     */
    static final Set<String> PERMISOS_BASE = Set.of(
            "ASIGNAR_ROLES", "VER_ROLES", "CREAR_ROLES", "MODIFICAR_ROLES", "ELIMINAR_ROLES",
            "ASIGNAR_PERMISOS", "VER_PERMISOS", "CREAR_PERMISOS", "MODIFICAR_PERMISOS", "ELIMINAR_PERMISOS",
            "VER_USUARIOS", "CREAR_USUARIOS", "MODIFICAR_USUARIOS", "ELIMINAR_USUARIOS",
            "PANEL_ADMIN", "DASHBOARD_ADMIN");

    /** True si el nombre corresponde a un permiso base (protegido por los triggers). */
    static boolean esPermisoBase(String nombre) {
        return nombre != null && PERMISOS_BASE.contains(nombre);
    }

    /** Rol que solo recibe permisos: no se modifica ni se elimina. */
    static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    /**
     * Usuarios protegidos: sin update, delete ni cambio de rol. "@admin" es el
     * usuario semilla (USER_ADMIN). Coincide con seguridad.es_usuario_protegido
     * de postgresql.sql; la comparacion ignora mayusculas, como la colacion
     * comun.ci que usan los triggers de proteccion.
     */
    static final Set<String> USUARIOS_PROTEGIDOS = Set.of("@admin", "@hehiachi");

    static boolean esUsuarioProtegido(String usuario) {
        return USUARIOS_PROTEGIDOS.stream().anyMatch(protegido -> protegido.equalsIgnoreCase(usuario));
    }

    static boolean esRolAdministrador(String nombre) {
        return ROL_ADMINISTRADOR.equalsIgnoreCase(nombre);
    }

    /**
     * Bloquea la autoasignacion: un usuario no puede cambiar sus propios roles
     * ni concederse/quitarse permisos individuales (evita la elevacion de
     * privilegios). El actor 0 (sistema/seeding) queda exento.
     */
    static void verificarNoAutoGestion(Long usuarioAfectado, Long actor) {
        if (usuarioAfectado != null && actor != null && actor != 0L && usuarioAfectado.equals(actor)) {
            throw new OperacionNoPermitidaException(
                    "No puede modificar sus propios roles o permisos.");
        }
    }

    private Protecciones() {
    }
}
