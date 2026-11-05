package oracle.admin.response;

import java.util.List;

/** Indicadores ejecutivos del gobierno de accesos (RBAC + overrides). */
public record AnaliticaAdminResponse(
        Kpis kpis,
        List<Conteo> coberturaPaneles,
        List<Conteo> usuariosPorRol,
        List<Mes> crecimiento) {

    /**
     * Cuentas, riesgos y catálogo. Los indicadores de riesgo cuentan solo
     * usuarios ACTIVOS (un bloqueado no puede entrar aunque le falte el rol).
     *
     * @param usuariosSinRol      activos sin ningún rol activo: acceso solo por excepción o ninguno.
     * @param usuariosConExcepcion activos con al menos un override (grant o deny).
     * @param usuariosSoloRol     activos con rol y sin overrides: gobernados 100 % por RBAC.
     * @param conectados          usuarios activos con una sesión vigente ahora.
     * @param altas30 / altasPrevias30 cuentas creadas en los últimos 30 días y en los 30 anteriores.
     */
    public record Kpis(
            long usuarios,
            long usuariosActivos,
            long usuariosSinRol,
            long usuariosConExcepcion,
            long usuariosSoloRol,
            long overridesGrant,
            long overridesDeny,
            long conectados,
            long altas30,
            long altasPrevias30,
            long roles,
            long rolesActivos,
            long rolesSinUsuarios,
            long rolesSinPermisos,
            long permisos,
            long permisosActivos) {
    }

    public record Conteo(String etiqueta, long valor) {
    }

    /** Mes "YYYY-MM": cuentas creadas en el mes y total registrado al cierre del mes. */
    public record Mes(String periodo, long altas, long total) {
    }
}
