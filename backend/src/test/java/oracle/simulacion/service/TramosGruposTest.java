package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;
import oracle.simulacion.service.TramosGrupos.Grupo;
import oracle.simulacion.service.TramosGrupos.Reparto;
import oracle.simulacion.service.TramosGrupos.Tramo;
import org.junit.jupiter.api.Test;

class TramosGruposTest {

  @Test
  void repartePorBultosYElSobranteVaALaMayorFraccion() {
    // 9 chutes útiles (15–18 y 22–26, sin 19–21). B 300, A 600, C 100 de 1000:
    // partes 2,7 / 5,4 / 0,9 → 2, 5 y 1 (mínimo); el chute que sobra va a B (0,7).
    List<Long> rango = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L);
    Reparto reparto =
        TramosGrupos.repartir(
            rango, List.of(grupo("B", 50, "300"), grupo("A", 50, "600"), grupo("C", 50, "100")));

    assertEquals(List.of(15L, 16L, 17L), chutes(reparto, 0));
    assertEquals(List.of(18L, 22L, 23L, 24L, 25L), chutes(reparto, 1));
    assertEquals(List.of(26L), chutes(reparto, 2));
    assertEquals(List.of(1, 2, 3), reparto.tramos().stream().map(Tramo::prioridad).toList());
    assertEquals(0, reparto.sinUsar());
  }

  @Test
  void cadaGrupoRecibeAlMenosUnChuteAunqueSuParteSeaMenor() {
    // 3 chutes: A 980, B 10, C 10. A se queda con 1 para que B y C tengan el suyo.
    Reparto reparto =
        TramosGrupos.repartir(
            rango(3), List.of(grupo("A", 9, "980"), grupo("B", 9, "10"), grupo("C", 9, "10")));

    assertEquals(List.of(1, 1, 1), tamanos(reparto));
  }

  @Test
  void enUnEmpateDecideLaPrioridad() {
    // 3 chutes y dos grupos iguales: el sobrante va al de mayor prioridad.
    Reparto reparto =
        TramosGrupos.repartir(rango(3), List.of(grupo("B", 9, "100"), grupo("A", 9, "100")));

    assertEquals(List.of(2, 1), tamanos(reparto));
    assertEquals("B", reparto.tramos().get(0).grupo());
  }

  @Test
  void ningunGrupoRecibeMasChutesQueRutasYElRestoQuedaLibre() {
    // 10 chutes: A tiene 2 rutas, B 3: reciben 2 y 3, y 5 chutes quedan libres.
    Reparto reparto =
        TramosGrupos.repartir(rango(10), List.of(grupo("A", 2, "900"), grupo("B", 3, "100")));

    assertEquals(List.of(2, 3), tamanos(reparto));
    assertEquals(5, reparto.sinUsar());
  }

  @Test
  void loQueUnGrupoNoPuedeUsarPasaAlOtro() {
    // 6 chutes: A (1 ruta, 900) se queda con 1; los otros 5 van a B.
    Reparto reparto =
        TramosGrupos.repartir(rango(6), List.of(grupo("A", 1, "900"), grupo("B", 20, "100")));

    assertEquals(List.of(1, 5), tamanos(reparto));
    assertEquals(0, reparto.sinUsar());
  }

  @Test
  void sinBultosTodosPesanIgual() {
    Reparto reparto =
        TramosGrupos.repartir(rango(4), List.of(grupo("A", 5, "0"), grupo("B", 5, "0")));

    assertEquals(List.of(2, 2), tamanos(reparto));
  }

  @Test
  void masGruposQueChutesNoSePuedeRepartir() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TramosGrupos.repartir(rango(1), List.of(grupo("A", 1, "1"), grupo("B", 1, "1"))));
  }

  @Test
  void siempreUsaTodoElRangoSiLasRutasAlcanzan() {
    for (int n = 3; n <= 30; n++) {
      Reparto reparto =
          TramosGrupos.repartir(
              rango(n), List.of(grupo("A", 99, "7"), grupo("B", 99, "13"), grupo("C", 99, "29")));
      assertEquals(n, tamanos(reparto).stream().mapToInt(Integer::intValue).sum(), "n=" + n);
      // Tramos seguidos, sin huecos ni solapes.
      assertEquals(rango(n), reparto.tramos().stream().flatMap((t) -> t.chutes().stream()).toList());
    }
  }

  private static Grupo grupo(String nombre, long rutas, String bultos) {
    return new Grupo(nombre, rutas, new BigDecimal(bultos));
  }

  private static List<Long> rango(int n) {
    return LongStream.rangeClosed(1, n).boxed().toList();
  }

  private static List<Long> chutes(Reparto reparto, int i) {
    return reparto.tramos().get(i).chutes();
  }

  private static List<Integer> tamanos(Reparto reparto) {
    return reparto.tramos().stream().map((t) -> t.chutes().size()).toList();
  }
}
