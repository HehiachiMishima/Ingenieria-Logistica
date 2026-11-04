package oracle.bitacora.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lee en un solo viaje a la base todo lo que hay que validar antes de guardar
 * una incidencia (5 catalogos + unicidad del ticket), en lugar de 6 consultas.
 * Cada subconsulta va por PK o por el indice unico del ticket.
 */
@Repository
public class ReferenciasIncidenciaRepository {

    private static final String SQL = """
            SELECT
              EXISTS(SELECT 1 FROM data_centro WHERE id = ?) AS centro,
              EXISTS(SELECT 1 FROM data_sistema WHERE id = ?) AS sistema,
              EXISTS(SELECT 1 FROM data_area WHERE id = ?) AS area,
              (SELECT id_data_sistema FROM data_incidencia WHERE id = ?) AS sistema_del_tipo,
              (SELECT id_data_area FROM data_responsable WHERE id = ?) AS area_del_responsable,
              EXISTS(SELECT 1 FROM incidencias WHERE lower(ticket) = lower(?) AND id <> ?) AS ticket_ocupado
            """;

    private final JdbcTemplate jdbc;

    public ReferenciasIncidenciaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param idExcluido incidencia que se esta editando (su propio ticket no
     *        cuenta como ocupado); 0 al crear.
     */
    public ReferenciasIncidencia consultar(Long idCentro, Long idSistema, Long idArea, Long idTipo,
            Long idResponsable, String ticket, long idExcluido) {
        return jdbc.queryForObject(SQL, (rs, n) -> new ReferenciasIncidencia(
                rs.getBoolean("centro"),
                rs.getBoolean("sistema"),
                rs.getBoolean("area"),
                rs.getObject("sistema_del_tipo", Long.class),
                rs.getObject("area_del_responsable", Long.class),
                rs.getBoolean("ticket_ocupado")),
                idCentro, idSistema, idArea, idTipo, idResponsable, ticket, idExcluido);
    }
}
