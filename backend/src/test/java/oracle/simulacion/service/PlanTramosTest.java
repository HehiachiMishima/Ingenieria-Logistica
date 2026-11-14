package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Buff primero, grupos alrededor y sin chutes vacíos, con los datos reales del registro Maestro. */
class PlanTramosTest {

  private static TramosGrupos.Grupo grupo(String nombre, long rutas, String bultos, long tope) {
    return new TramosGrupos.Grupo(nombre, rutas, new BigDecimal(bultos), tope);
  }

  private static PlanTramos.Pesada pesada(String grupo, String bultos) {
    return new PlanTramos.Pesada(grupo, new BigDecimal(bultos));
  }

  private static List<Long> chutes(TramosGrupos.Tramo tramo) {
    return tramo.chutes();
  }

  @Test
  void cl1ElBuffEsDelGrupoDeLaRutaMasPesadaYSuTramoLoRodea() {
    // CL1, solo diarias: el buff 6 cae hoy en el grupo 2 (224,13); la más pesada es del 4 (272,15).
    List<Long> rango = LongStream.rangeClosed(1, 14).boxed().toList();
    List<TramosGrupos.Grupo> grupos =
        List.of(
            grupo("1", 9, "1511.74", 9),
            grupo("2", 7, "1180.68", 7),
            grupo("3", 7, "865.13", 7),
            grupo("4", 6, "891.61", 6),
            grupo("5", 3, "257.59", 3),
            grupo("6", 1, "80.45", 1));

    PlanTramos.Plan plan =
        PlanTramos.planificar(
            rango, grupos, Set.of(6L), List.of(pesada("4", "272.15"), pesada("1", "262.83")));

    assertEquals("4", plan.duenos().get(6L).grupo());
    assertEquals(List.of("1", "4", "2", "3", "5", "6"), plan.tramos().stream().map(TramosGrupos.Tramo::grupo).toList());
    assertEquals(List.of(1L, 2L, 3L, 4L), chutes(plan.tramos().get(0)));
    assertEquals(List.of(5L, 6L, 7L), chutes(plan.tramos().get(1)), "el grupo 4 rodea al buff 6");
    assertEquals(rango, plan.rango(), "los 14 chutes se usan");
    assertEquals(0, plan.sinUsar());
  }

  @Test
  void cl3LosDosBuffSonDelGrupo2YSuTramoLosRodea() {
    List<Long> rango = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L);
    // Tope = su día más cargado. Grupo 1: 2 diarias + 2 L-X-V → 4. Grupo 2: 10 L-X-V → 10.
    List<TramosGrupos.Grupo> grupos = List.of(grupo("1", 4, "1219.15", 4), grupo("2", 10, "4468.54", 10));

    PlanTramos.Plan plan =
        PlanTramos.planificar(
            rango,
            grupos,
            Set.of(18L, 25L),
            List.of(pesada("2", "1531.63"), pesada("2", "878.19"), pesada("2", "785.12")));

    assertEquals("2", plan.duenos().get(18L).grupo());
    assertEquals("2", plan.duenos().get(25L).grupo());
    assertEquals(List.of(15L, 16L), chutes(plan.tramos().get(0)));
    assertEquals(List.of(17L, 18L, 22L, 23L, 24L, 25L), chutes(plan.tramos().get(1)));
    assertEquals(0, plan.sinUsar(), "2 + 6 = los 8 chutes");
  }

  @Test
  void cl4SoloSaleDelRangoLoQueNingunDiaLlena() {
    List<Long> rango = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L);
    // Sin diarias: L-X-V 10, M-J-S 5 → tope 10 (su día más cargado): el 28 y el 29 sobran.
    PlanTramos.Plan plan =
        PlanTramos.planificar(
            rango,
            List.of(grupo("1", 10, "5249.38", 10)),
            Set.of(18L, 25L),
            List.of(pesada("1", "1246.69"), pesada("1", "1172.81")));

    List<Long> usados = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L, 27L);
    assertEquals(usados, plan.rango());
    assertEquals(usados, chutes(plan.tramos().getFirst()));
    assertEquals(2, plan.sinUsar());
    assertTrue(plan.sinDueno().isEmpty());
  }

  @Test
  void sinBuffLosGruposVanEnOrdenNaturalDesdeElInicio() {
    List<Long> rango = LongStream.rangeClosed(1, 6).boxed().toList();
    PlanTramos.Plan plan =
        PlanTramos.planificar(
            rango,
            List.of(grupo("1", 3, "300", 3), grupo("2", 2, "200", 2)),
            Set.of(),
            List.of(pesada("1", "150")));

    assertEquals(List.of(1L, 2L, 3L), chutes(plan.tramos().get(0)));
    assertEquals(List.of(4L, 5L), chutes(plan.tramos().get(1)));
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L), plan.rango(), "el 6 sobra: queda fuera del rango, no vacío");
  }

  @Test
  void unBuffQueSuGrupoNoAlcanzaQuedaSinDueno() {
    // Los dos buff quedan a 6 chutes y el grupo 1 solo llena 2: el segundo no puede ser suyo.
    List<Long> rango = LongStream.rangeClosed(1, 6).boxed().toList();
    PlanTramos.Plan plan =
        PlanTramos.planificar(
            rango,
            List.of(grupo("1", 2, "1700", 2), grupo("2", 4, "400", 4)),
            Set.of(1L, 6L),
            List.of(pesada("1", "900"), pesada("1", "800"), pesada("2", "100")));

    assertEquals("1", plan.duenos().get(1L).grupo());
    assertEquals(List.of(6L), plan.sinDueno());
    assertTrue(chutes(plan.tramos().getFirst()).contains(1L));
  }

  @Test
  void dosBuffDeGruposDistintosRodeadosCadaUnoPorSuGrupo() {
    List<Long> rango = LongStream.rangeClosed(1, 6).boxed().toList();
    PlanTramos.Plan plan =
        PlanTramos.planificar(
            rango,
            List.of(grupo("1", 3, "300", 3), grupo("2", 3, "300", 3)),
            Set.of(2L, 5L),
            List.of(pesada("2", "500"), pesada("1", "400")));

    // El 2 es del grupo 2 y el 5 del grupo 1: el grupo 2 va primero (1-3) y el 1 después (4-6).
    assertEquals("2", plan.duenos().get(2L).grupo());
    assertEquals("1", plan.duenos().get(5L).grupo());
    assertEquals(List.of(1L, 2L, 3L), chutes(plan.tramos().get(0)));
    assertEquals("2", plan.tramos().get(0).grupo());
    assertEquals(List.of(4L, 5L, 6L), chutes(plan.tramos().get(1)));
  }
}
