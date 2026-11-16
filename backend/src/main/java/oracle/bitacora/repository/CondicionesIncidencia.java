package oracle.bitacora.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import oracle.bitacora.request.EstadoIncidencia;
import oracle.bitacora.request.FiltroIncidencia;
import oracle.common.config.ZonaNegocio;
import oracle.common.support.Filtros;

/**
 * Unica traduccion de {@link FiltroIncidencia} a condiciones. La consumen el
 * WHERE SQL de la analitica ({@link CondicionIncidencia}) y la Specification
 * JPA del listado y la exportacion (IncidenciaService): ambos recorren la
 * misma lista con un switch exhaustivo sobre {@link Condicion}, asi un filtro
 * nuevo se agrega aqui una vez y el compilador obliga a traducirlo en los dos.
 *
 * El orden respeta el de los indices compuestos (id_data_* antes de creado_en).
 */
public final class CondicionesIncidencia {

    /** Una condicion del filtro, ya normalizada. */
    public sealed interface Condicion {
    }

    /** Igualdad sobre una FK: columna SQL (alias i) y atributo JPA. */
    public record Igual(String columna, String atributo, Long valor) implements Condicion {
    }

    /** creado_en >= instante (inicio del dia del negocio). */
    public record Desde(Instant instante) implements Condicion {
    }

    /** creado_en < instante (inicio del dia siguiente: intervalo semiabierto). */
    public record Hasta(Instant instante) implements Condicion {
    }

    /** Ticket o descripcion contienen el texto: patron LIKE ya escapado, en minusculas. */
    public record Texto(String patron) implements Condicion {
    }

    /** Abierta = sin cierre (indice parcial idx_incidencias_abiertas); cerrada = con cierre. */
    public record Estado(EstadoIncidencia estado) implements Condicion {
    }

    public static List<Condicion> de(FiltroIncidencia filtro, ZonaNegocio zona) {
        List<Condicion> condiciones = new ArrayList<>();
        if (filtro == null) {
            return condiciones;
        }
        igual(condiciones, "i.id_data_centro", "idDataCentro", filtro.centro());
        igual(condiciones, "i.id_data_sistema", "idDataSistema", filtro.sistema());
        igual(condiciones, "i.id_data_area", "idDataArea", filtro.area());
        igual(condiciones, "i.id_data_responsable", "idDataResponsable", filtro.responsable());
        igual(condiciones, "i.id_data_incidencia", "idDataIncidencia", filtro.tipo());
        if (filtro.desde() != null) {
            condiciones.add(new Desde(zona.inicioDelDia(filtro.desde())));
        }
        if (filtro.hasta() != null) {
            condiciones.add(new Hasta(zona.inicioDelDia(filtro.hasta().plusDays(1))));
        }
        if (filtro.texto() != null && !filtro.texto().isBlank()) {
            condiciones.add(new Texto("%" + Filtros.escaparLike(filtro.texto().trim().toLowerCase()) + "%"));
        }
        if (filtro.estado() != null) {
            condiciones.add(new Estado(filtro.estado()));
        }
        return condiciones;
    }

    private static void igual(List<Condicion> condiciones, String columna, String atributo, Long valor) {
        if (valor != null) {
            condiciones.add(new Igual(columna, atributo, valor));
        }
    }

    private CondicionesIncidencia() {
    }
}
