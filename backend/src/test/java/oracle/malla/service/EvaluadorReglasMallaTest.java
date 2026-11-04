package oracle.malla.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import oracle.malla.model.MallaDatos;
import oracle.malla.model.MallaDatos.Centro;
import oracle.malla.model.MallaDatos.Dotacion;
import oracle.malla.model.MallaDatos.Flujo;
import oracle.malla.model.MallaDatos.Ola;
import oracle.malla.model.MallaDatos.Turno;
import oracle.malla.response.HallazgoMallaResponse;
import org.junit.jupiter.api.Test;

class EvaluadorReglasMallaTest {

    private static final Centro CENTRO = new Centro(7L, "9999", "Prueba");
    private static final List<Turno> TURNOS = List.of(
            turno(1L, "T1", "07:00", "16:00"),
            turno(2L, "T2", "13:00", "22:00"));

    private final EvaluadorReglasMalla evaluador = new EvaluadorReglasMalla();

    @Test
    void secuenciaDetectaUnLanzamientoAntesDeLaCaida() {
        List<HallazgoMallaResponse> hallazgos = evaluar(ReglasMallaCatalogo.OLA_SECUENCIA,
                datos(List.of(ola(1L, "Ola 1", "08:00", "07:00", "13:00", "15:00"),
                        ola(2L, "Ola 2", "13:00", "15:00", "21:00", "23:00"))));

        assertEquals(1, hallazgos.size());
        assertEquals("Cluster 1 · Ola 1", hallazgos.get(0).entidad());
    }

    @Test
    void lanzamientoTrasMedianocheSeJuzgaPorSuOrdenEnElCluster() {
        List<HallazgoMallaResponse> hallazgos = evaluar(ReglasMallaCatalogo.OLA_LANZAMIENTO,
                datos(List.of(ola(1L, "Nocturna", "05:00", "07:00", "13:00", "15:00"),
                        ola(2L, "Ola 2", "13:00", "15:00", "21:00", "23:00"),
                        ola(3L, "Ola 3", "22:00", "00:30", "05:00", "06:00"))));

        // «Nocturna» es la primera del día (límite 07:00) aunque su nombre no diga «1»;
        // la de las 00:30 es la tercera y se pasa 9 h 30 min del último límite (15:00).
        assertEquals(1, hallazgos.size());
        assertEquals("Cluster 1 · Ola 3", hallazgos.get(0).entidad());
        assertTrue(hallazgos.get(0).detalle().contains("9 h 30 min"), hallazgos.get(0).detalle());
    }

    @Test
    void dotacionSinFilasNoPasa() {
        List<HallazgoMallaResponse> hallazgos = evaluar(ReglasMallaCatalogo.DOTACION_PLANIFICADA,
                new MallaDatos(List.of(CENTRO), TURNOS, List.of(), List.of(), List.of(), List.of(),
                        List.of(new Dotacion(1L, 7L, 1L, "REC", 12))));

        // REC falta en T2; los otros cuatro procesos faltan en los dos turnos.
        assertEquals(5, hallazgos.size());
        assertEquals("9999", hallazgos.get(0).centroCodigo());
        assertTrue(hallazgos.get(0).detalle().contains("T2"), hallazgos.get(0).detalle());
    }

    @Test
    void unPasoPuedeTerminarJustoAlCierreDelTurno() {
        List<HallazgoMallaResponse> hallazgos = evaluar(ReglasMallaCatalogo.FLUJO_VENTANA_TURNO,
                new MallaDatos(List.of(CENTRO), TURNOS,
                        List.of(new Flujo(1L, 7L, "REC", 1, "OPS", hora("07:00"), hora("22:00"), "Descarga", true),
                                new Flujo(2L, 7L, "REC", 2, "OPS", hora("20:00"), hora("23:00"), "Tarde", true)),
                        List.of(), List.of(), List.of(), List.of()));

        assertEquals(1, hallazgos.size());
        assertEquals("Recepción · paso 2", hallazgos.get(0).entidad());
    }

    @Test
    void elHuecoQueCruzaLaMedianocheEsUnoSolo() {
        List<HallazgoMallaResponse> hallazgos = evaluar(ReglasMallaCatalogo.TURNO_COBERTURA,
                new MallaDatos(List.of(CENTRO), TURNOS, List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(1, hallazgos.size());
        assertTrue(hallazgos.get(0).obtenido().contains("22:00–07:00 (9 h)"), hallazgos.get(0).obtenido());
        assertTrue(hallazgos.get(0).obtenido().startsWith("Cubre 15 h"), hallazgos.get(0).obtenido());
    }

    private List<HallazgoMallaResponse> evaluar(String regla, MallaDatos datos) {
        return evaluador.evaluar(regla, Map.of(), datos);
    }

    private static MallaDatos datos(List<Ola> olas) {
        return new MallaDatos(List.of(CENTRO), TURNOS, List.of(), olas, List.of(), List.of(), List.of());
    }

    private static Turno turno(Long id, String codigo, String inicio, String fin) {
        return new Turno(id, 7L, codigo, codigo, hora(inicio), hora(fin), null, true);
    }

    private static Ola ola(Long id, String nombre, String pedido, String lanzamiento, String cierre, String despacho) {
        return new Ola(id, 7L, "1", nombre, hora(pedido), hora(lanzamiento), hora(cierre), hora(despacho), true);
    }

    private static LocalTime hora(String valor) {
        return LocalTime.parse(valor);
    }
}
