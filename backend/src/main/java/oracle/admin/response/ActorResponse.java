package oracle.admin.response;

/** Nombre de usuario de quien creó o modificó un registro (columnas creado_por / actualizado_por). */
public record ActorResponse(Long id, String usuario) {
}
