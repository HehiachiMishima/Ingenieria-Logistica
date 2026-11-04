package oracle.common.security;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Refresh tokens emitidos (tabla sesiones_refresh). Solo se guarda el hash
 * SHA-256 del token. Cada login abre una «familia» (UUID); cada renovación
 * revoca el token usado y crea otro en la misma familia. Los vencimientos se
 * calculan en la base con now() sobre TIMESTAMPTZ: no dependen de la zona de
 * la JVM ni de la sesión.
 *
 * Vive en common porque Administración también revoca sesiones (al cambiar
 * la contraseña de un usuario o desactivarlo).
 */
@Repository
public class SesionesRefresh {

    /** Token encontrado al renovar: de quién es, su familia y si ya se usó o venció. */
    public record Fila(Long id, Long usuarioId, String familia, boolean revocado, boolean vencido) {
    }

    private final JdbcTemplate jdbc;

    public SesionesRefresh(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Primer token de una familia nueva (login). */
    public void abrir(Long usuarioId, String familia, String hash, long inactividadS, long maximoS, String ip) {
        jdbc.update("""
                INSERT INTO sesiones_refresh
                    (usuario_id, familia, token_hash, expira_en, familia_expira_en, ip)
                VALUES (?, ?::uuid, ?,
                        now() + make_interval(secs => LEAST(?, ?)),
                        now() + make_interval(secs => ?), ?)
                """, usuarioId, familia, hash, inactividadS, maximoS, maximoS, ip);
    }

    /** Token por su hash, bloqueado hasta el fin de la transacción: dos renovaciones no lo usan a la vez. */
    public Optional<Fila> bloquear(String hash) {
        return jdbc.query("""
                SELECT id, usuario_id, familia::text AS familia, revocado_en IS NOT NULL AS revocado,
                       expira_en <= now() AS vencido
                FROM sesiones_refresh WHERE token_hash = ? FOR UPDATE
                """, (rs, n) -> new Fila(rs.getLong("id"), rs.getLong("usuario_id"), rs.getString("familia"),
                rs.getBoolean("revocado"), rs.getBoolean("vencido")), hash).stream().findFirst();
    }

    /**
     * Revoca el token usado y crea el siguiente en su familia: vence por
     * inactividad, pero nunca después del máximo de la familia.
     */
    public void rotar(Fila usado, String hashNuevo, long inactividadS, String ip) {
        jdbc.update("UPDATE sesiones_refresh SET revocado_en = now() WHERE id = ?", usado.id());
        jdbc.update("""
                INSERT INTO sesiones_refresh
                    (usuario_id, familia, token_hash, expira_en, familia_expira_en, ip)
                SELECT usuario_id, familia, ?,
                       LEAST(now() + make_interval(secs => ?), familia_expira_en), familia_expira_en, ?
                FROM sesiones_refresh WHERE id = ?
                """, hashNuevo, inactividadS, ip, usado.id());
    }

    /** Segundos que le quedan al token (0 si no existe o ya venció). */
    public long segundosRestantes(String hash) {
        Long restante = jdbc.queryForObject("""
                SELECT GREATEST(COALESCE(MAX(floor(EXTRACT(EPOCH FROM expira_en - now()))), 0), 0)::bigint
                FROM sesiones_refresh WHERE token_hash = ?
                """, Long.class, hash);
        return restante == null ? 0 : restante;
    }

    public Optional<String> familiaDe(String hash) {
        return jdbc.queryForList("SELECT familia::text FROM sesiones_refresh WHERE token_hash = ?", String.class, hash)
                .stream().findFirst();
    }

    public void revocarFamilia(String familia) {
        jdbc.update("UPDATE sesiones_refresh SET revocado_en = now() WHERE familia = ?::uuid AND revocado_en IS NULL",
                familia);
    }

    /** Cierra todas las sesiones del usuario (contraseña cambiada por otro, cuenta desactivada). */
    public void revocarUsuario(Long usuarioId) {
        jdbc.update("""
                UPDATE sesiones_refresh SET revocado_en = now()
                WHERE usuario_id = ? AND revocado_en IS NULL
                """, usuarioId);
    }

    /** Cierra las demás sesiones del usuario y conserva la familia indicada (la del propio navegador). */
    public void revocarUsuarioExcepto(Long usuarioId, String familia) {
        jdbc.update("""
                UPDATE sesiones_refresh SET revocado_en = now()
                WHERE usuario_id = ? AND familia <> ?::uuid AND revocado_en IS NULL
                """, usuarioId, familia);
    }

    /**
     * Borra, en lotes, los tokens cuya familia venció hace mas de un dia, de todos
     * los usuarios (purgar() solo limpia al que vuelve a entrar; los que no vuelven
     * dejarian la tabla creciendo sin limite). Lotes cortos: nunca un DELETE largo
     * que bloquee las renovaciones. Devuelve las filas borradas.
     */
    public int purgarVencidas(int lote, int maxLotes) {
        int total = 0;
        for (int i = 0; i < maxLotes; i++) {
            int borradas = jdbc.update("""
                    DELETE FROM sesiones_refresh
                    WHERE id IN (SELECT id FROM sesiones_refresh
                                 WHERE familia_expira_en < now() - INTERVAL '1 day'
                                 LIMIT ?)
                    """, lote);
            total += borradas;
            if (borradas < lote) {
                break;
            }
        }
        return total;
    }

    /** Borra los tokens del usuario cuya familia ya venció: la tabla no crece sin límite. */
    public void purgar(Long usuarioId) {
        jdbc.update("""
                DELETE FROM sesiones_refresh
                WHERE usuario_id = ? AND familia_expira_en < now() - INTERVAL '1 day'
                """, usuarioId);
    }
}
