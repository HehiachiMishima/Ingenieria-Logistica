package oracle.auth;

import oracle.admin.response.UsuarioResponse;

import java.time.Instant;
import java.util.List;

/**
 * Resultado del login o de una renovacion: token Bearer (corto) + identidad +
 * permisos. renovableHasta es hasta cuando el refresh token de la cookie puede
 * renovarlo sin pedir la contrasena (se extiende con cada renovacion).
 */
public record AuthResponse(
        String token,
        String tipo,
        Instant expiraEn,
        Instant renovableHasta,
        UsuarioResponse usuario,
        List<String> permisos) {
}
