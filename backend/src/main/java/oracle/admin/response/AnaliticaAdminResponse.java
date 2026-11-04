package oracle.admin.response;

import java.util.List;

/** Indicadores ejecutivos del gobierno de accesos (RBAC + overrides). */
public record AnaliticaAdminResponse(
        Kpis kpis,
        List<Conteo> coberturaPaneles) {

    /**
     * @param usuariosSinRol riesgo: cuentas que solo dependen de overrides o no tienen acceso.
     * @param overridesDeny / overridesGrant excepciones individuales al RBAC.
     */
    public record Kpis(
            long usuarios,
            long usuariosActivos,
            long usuariosSinRol,
            long overridesGrant,
            long overridesDeny) {
    }

    public record Conteo(String etiqueta, long valor) {
    }
}
