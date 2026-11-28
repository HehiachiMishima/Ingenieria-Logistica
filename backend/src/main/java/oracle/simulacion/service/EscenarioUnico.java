package oracle.simulacion.service;

import java.util.List;
import oracle.simulacion.model.ParametroChute;
import oracle.simulacion.model.Predeterminados;
import oracle.simulacion.repository.SimulacionLotesRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * El escenario único con el que trabajan Configuración y el Dashboard: una fila de {@code
 * archivos_excel} marcada {@code es_unico}. Nace vacío (sin clusters ni rutas): las rutas se crean
 * en DATA y los clusters se agregan en Configuración.
 */
@Component
public class EscenarioUnico {

  private final JdbcTemplate jdbc;
  private final SimulacionLotesRepository lotes;
  private final PlanoChutes plano;

  public EscenarioUnico(JdbcTemplate jdbc, SimulacionLotesRepository lotes, PlanoChutes plano) {
    this.jdbc = jdbc;
    this.lotes = lotes;
    this.plano = plano;
  }

  /** Id del escenario, o null si aún no se creó. */
  public Long buscar() {
    // Q: uq_archivos_es_unico.
    List<Long> ids = jdbc.queryForList("SELECT id FROM simulacion.archivos_excel WHERE es_unico", Long.class);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  /**
   * Id del escenario; si no existe, lo crea con la configuración y los chutes de siempre (velocidad
   * y buff predeterminados, todos los chutes visibles del plano). Debe llamarse dentro de una
   * transacción de escritura.
   */
  public long asegurar(Long actor) {
    Long id = buscar();
    if (id != null) {
      return id;
    }
    // Dos primeras peticiones a la vez no crean dos escenarios (el índice único también lo impide).
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('simulacion.escenario_unico'))::text", String.class);
    id = buscar();
    if (id != null) {
      return id;
    }
    Long nuevo =
        jdbc.queryForObject(
            """
            INSERT INTO simulacion.archivos_excel
                (nombre_registro, nombre_archivo, estado, es_unico, creado_por, actualizado_por)
            VALUES ('Simulación Titan', 'Escenario', 'PROYECCION', TRUE, ?, ?)
            RETURNING id
            """,
            Long.class,
            actor,
            actor);
    long archivoId = nuevo == null ? 0 : nuevo;
    Predeterminados predeterminados = SimulacionPredeterminadosService.actuales(lotes);
    List<Long> chutes = plano.idsVisibles();
    lotes.insertarConfig(archivoId, predeterminados.velocidad(), chutes.size(), actor);
    // Nace con el buff y los chutes bloqueados (solo a mano) de los predeterminados; sin ellos,
    // los del plano (19, 20 y 21).
    lotes.insertarParametrosChute(
        archivoId,
        chutes,
        ParametroChute.OPERARIOS_INICIALES,
        predeterminados.chutesBuff(),
        predeterminados.bloqueadosO(plano.idsManualesPorDefecto()));
    return archivoId;
  }
}
