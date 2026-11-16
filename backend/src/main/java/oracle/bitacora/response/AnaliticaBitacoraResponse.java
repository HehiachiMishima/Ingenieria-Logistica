package oracle.bitacora.response;

import java.time.Instant;
import java.util.List;
import oracle.common.response.CeldaMatriz;

/** Analitica del panel Bitacora calculada en SQL para el filtro recibido. */
public record AnaliticaBitacoraResponse(
        Kpis kpis,
        List<Grupo> sistemas,
        List<Grupo> centros,
        List<Grupo> areas,
        List<Tipo> tipos,
        List<CeldaMatriz> centroSistema,
        List<FilaEjecutiva> ejecutivo,
        Reincidencia reincidencia,
        /** Abiertas ahora: se calcula en cada peticion (no se cachea, depende de la hora). */
        Abiertas abiertas) {

    /** Misma analitica con el bloque de abiertas recien calculado. */
    public AnaliticaBitacoraResponse conAbiertas(Abiertas actuales) {
        return new AnaliticaBitacoraResponse(kpis, sistemas, centros, areas, tipos, centroSistema, ejecutivo,
                reincidencia, actuales);
    }

    /**
     * Incidencias abiertas del filtro por antiguedad (calculada contra now() en
     * la base) y la mas antigua; el cliente hace avanzar su antiguedad.
     */
    public record Abiertas(
            long total,
            long menosDeUnaHora,
            long deUnaACuatroHoras,
            long masDeCuatroHoras,
            Instant desdeMasAntigua,
            String ticketMasAntiguo) {

        public static final Abiertas NINGUNA = new Abiertas(0, 0, 0, 0, null, null);
    }

    /**
     * @param totalPeriodoAnterior total del periodo inmediatamente anterior de igual
     *        duracion; null si el filtro no tiene rango de fechas completo.
     */
    public record Kpis(
            long total,
            long resueltas,
            long segundosSolucion,
            long mttrSegundos,
            long sistemasAfectados,
            long centrosAfectados,
            Long totalPeriodoAnterior) {
    }

    /** Agregado por una dimension: volumen y tiempo medio de solucion. */
    public record Grupo(String etiqueta, long total, long mttrSegundos) {
    }

    /** Tipo de incidencia con su sistema (el tipo pertenece a un sistema). */
    public record Tipo(String tipo, String sistema, long total, long mttrSegundos) {
    }

    /**
     * Fila del cuadro ejecutivo (una por sistema).
     *
     * @param id id del sistema: el cliente lo usa para pedir sus casos mas largos.
     */
    public record FilaEjecutiva(
            long id,
            String etiqueta,
            long total,
            long segundos,
            long mttrSegundos,
            long maxSegundos) {
    }

    /**
     * Reincidencias: un caso reincide si su mismo tipo ya ocurrio en el mismo
     * centro dentro de los {@code ventanaDias} anteriores (dentro del filtro).
     */
    public record Reincidencia(int ventanaDias, long total, long reincidentes, List<Repetido> repetidos) {
    }

    /** Combinacion centro + tipo que mas reincide. */
    public record Repetido(String centro, String tipo, String sistema, long casos, long reincidencias) {
    }
}
