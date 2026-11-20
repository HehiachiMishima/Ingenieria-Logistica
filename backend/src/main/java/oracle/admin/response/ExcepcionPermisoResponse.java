package oracle.admin.response;

/** Excepción vigente de un usuario sobre un permiso: TRUE concede, FALSE deniega. */
public record ExcepcionPermisoResponse(Long permisoId, boolean otorgado) {
}
