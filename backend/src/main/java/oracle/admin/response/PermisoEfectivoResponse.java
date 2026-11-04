package oracle.admin.response;

/** Fila de v_permisos_efectivos: rol(TRUE) + override(otorgado), antes de MIN(). */
public record PermisoEfectivoResponse(Long usuarioId, Long permisoId, Boolean otorgado, String origen) {
}
