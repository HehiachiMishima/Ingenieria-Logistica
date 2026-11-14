package oracle.simulacion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Balanceo interno de una frecuencia: primero por grupos, después el buff, sin huecos. */
class RepartoFrecuenciaTest {

  /** Registro «principal», CL3: rango 15 → 26 sin 19-21, buff en 18, 22 y 25. */
  private static final List<Long> RANGO_CL3 = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L);
  private static final Set<Long> BUFF_CL3 = Set.of(18L, 22L, 25L);

  private static RepartoFrecuencia.Ruta ruta(long id, String grupo, String bultos, long chute) {
    return new RepartoFrecuencia.Ruta(id, grupo, new BigDecimal(bultos), chute);
  }

  /** Solo las rutas L-X-V de CL3 (las diarias no cuentan: cada frecuencia es independiente). */
  private static List<RepartoFrecuencia.Ruta> lxvCl3() {
    return List.of(
        ruta(3, "1", "145.11", 15), ruta(4, "1", "380.53", 15),
        ruta(10, "3", "455.45", 16), ruta(11, "3", "360.48", 16), ruta(12, "3", "217.22", 17),
        ruta(13, "3", "270.2", 17), ruta(14, "3", "338.15", 17),
        ruta(15, "4", "460.44", 18), ruta(16, "4", "66.41", 18), ruta(17, "4", "70.53", 18), ruta(18, "4", "96.81", 18),
        ruta(20, "6", "784.49", 26));
  }

  private static List<RepartoFrecuencia.Ruta> mjsCl3() {
    return List.of(
        ruta(5, "1", "556.61", 15),
        ruta(6, "2", "785.12", 23), ruta(7, "2", "611.6", 24), ruta(8, "2", "662", 24), ruta(9, "2", "878.19", 25),
        ruta(19, "5", "1531.63", 22));
  }

  private static Long chuteDe(RepartoFrecuencia.Plan plan, long rutaId) {
    return plan.destino().get(rutaId);
  }

  /** Lo que la verificación interna exige, comprobado desde afuera. */
  private static void sinHuecosYUnGrupoPorChute(List<Long> rango, List<RepartoFrecuencia.Ruta> rutas, RepartoFrecuencia.Plan plan) {
    Map<Long, String> grupoEn = new HashMap<>();
    TreeMap<Integer, Long> usadas = new TreeMap<>();
    for (RepartoFrecuencia.Ruta r : rutas) {
      Long chute = plan.destino().get(r.id());
      assertTrue(rango.contains(chute), "ruta " + r.id() + " fuera del rango");
      String previo = grupoEn.putIfAbsent(chute, r.grupo());
      assertTrue(previo == null || previo.equals(r.grupo()), "dos grupos en " + chute);
      usadas.put(rango.indexOf(chute), chute);
    }
    assertEquals(usadas.size(), usadas.lastKey() - usadas.firstKey() + 1, "hueco entre los chutes usados");
    assertEquals(Math.min(rango.size(), rutas.size()), usadas.size(), "usa todos los chutes que puede llenar");
  }

  @Test
  void lxvCl3UsaLosNueveChutesYElGrupoMayorTieneBuff() {
    RepartoFrecuencia.Plan plan =
        RepartoFrecuencia.planificar(RANGO_CL3, BUFF_CL3, List.of("1", "3", "4", "6"), lxvCl3());

    sinHuecosYUnGrupoPorChute(RANGO_CL3, lxvCl3(), plan);
    assertEquals("3", plan.mayor(), "el G3 suma 1.641,50 bultos");
    assertTrue(plan.buffs().containsValue("3"), plan.buffs().toString());
    // El chute más cargado del G3 es un buff, y la 784,49 (sola en su columna) también va a uno.
    Long masCargadoG3 =
        plan.carga().entrySet().stream()
            .filter((e) -> "3".equals(grupoDe(plan, lxvCl3(), e.getKey())))
            .max(Map.Entry.comparingByValue())
            .orElseThrow()
            .getKey();
    assertTrue(BUFF_CL3.contains(masCargadoG3), plan.carga().toString());
    assertTrue(BUFF_CL3.contains(chuteDe(plan, 20)), plan.destino().toString());
  }

  @Test
  void mjsCl3LlevaLasMasPesadasALosBuffSinDejarHuecos() {
    RepartoFrecuencia.Plan plan =
        RepartoFrecuencia.planificar(RANGO_CL3, BUFF_CL3, List.of("1", "2", "5"), mjsCl3());

    sinHuecosYUnGrupoPorChute(RANGO_CL3, mjsCl3(), plan);
    assertEquals("2", plan.mayor(), "el G2 suma 2.936,91 bultos");
    assertEquals(6, plan.usados().size(), "6 rutas: 6 chutes seguidos, los demás en un extremo");
    // Sin diarias que la estorben, la de 1.531,63 también llega a un buff.
    assertTrue(BUFF_CL3.contains(chuteDe(plan, 19)), plan.destino().toString());
    assertTrue(BUFF_CL3.contains(chuteDe(plan, 9)), "la más pesada del G2 (878,19) a un buff");
  }

  @Test
  void elMismoDatoEnOtroOrdenDaElMismoResultado() {
    List<RepartoFrecuencia.Ruta> barajadas = new ArrayList<>(lxvCl3());
    Collections.shuffle(barajadas, new Random(7));

    RepartoFrecuencia.Plan uno = RepartoFrecuencia.planificar(RANGO_CL3, BUFF_CL3, List.of("1", "3", "4", "6"), lxvCl3());
    RepartoFrecuencia.Plan otro = RepartoFrecuencia.planificar(RANGO_CL3, BUFF_CL3, List.of("1", "3", "4", "6"), barajadas);

    assertEquals(uno.destino(), otro.destino());
  }

  @Test
  void sinBuffConMenosRutasQueChutesLosLibresQuedanAlFinal() {
    List<Long> rango = LongStream.rangeClosed(1, 6).boxed().toList();
    List<RepartoFrecuencia.Ruta> rutas = List.of(ruta(1, "A", "100", 1), ruta(2, "A", "90", 1), ruta(3, "A", "80", 1));

    RepartoFrecuencia.Plan plan = RepartoFrecuencia.planificar(rango, Set.of(), List.of("A"), rutas);

    assertEquals(List.of(1L, 2L, 3L), plan.usados());
    sinHuecosYUnGrupoPorChute(rango, rutas, plan);
  }

  @Test
  void conMenosRutasQueChutesElBloqueSeCorreHastaElBuff() {
    List<Long> rango = LongStream.rangeClosed(1, 6).boxed().toList();
    List<RepartoFrecuencia.Ruta> rutas = List.of(ruta(1, "A", "500", 1), ruta(2, "A", "100", 1));

    RepartoFrecuencia.Plan plan = RepartoFrecuencia.planificar(rango, Set.of(5L), List.of("A"), rutas);

    assertEquals(5L, chuteDe(plan, 1), "la más pesada al buff");
    assertEquals(2, plan.usados().size());
    sinHuecosYUnGrupoPorChute(rango, rutas, plan);
  }

  @Test
  void elGrupoMayorVaAlBuffConSuGrupoCompleto() {
    // Grupo B pesa más: sus tres columnas se corren al buff (chute 5); la más cargada queda en él.
    List<Long> rango = LongStream.rangeClosed(1, 6).boxed().toList();
    List<RepartoFrecuencia.Ruta> rutas =
        List.of(
            ruta(1, "A", "100", 1), ruta(2, "A", "100", 2), ruta(3, "A", "100", 3),
            ruta(4, "B", "300", 4), ruta(5, "B", "250", 4), ruta(6, "B", "200", 6));

    RepartoFrecuencia.Plan plan = RepartoFrecuencia.planificar(rango, Set.of(5L), List.of("A", "B"), rutas);

    assertEquals("B", plan.buffs().get(5L));
    assertEquals(5L, chuteDe(plan, 4), "la columna más cargada del grupo mayor al buff");
    sinHuecosYUnGrupoPorChute(rango, rutas, plan);
  }

  @Test
  void primeroPorGruposLasColumnasQuedanParejas() {
    // Un grupo con seis rutas en un solo chute y tres chutes: queda 300 / 300 / 300.
    List<Long> rango = List.of(1L, 2L, 3L);
    List<RepartoFrecuencia.Ruta> rutas =
        List.of(
            ruta(1, "A", "200", 1), ruta(2, "A", "200", 1), ruta(3, "A", "200", 1),
            ruta(4, "A", "100", 1), ruta(5, "A", "100", 1), ruta(6, "A", "100", 1));

    RepartoFrecuencia.Plan plan = RepartoFrecuencia.planificar(rango, Set.of(), List.of("A"), rutas);

    plan.carga().values().forEach((carga) -> assertEquals(0, new BigDecimal("300").compareTo(carga), plan.carga().toString()));
  }

  @Test
  void milesDeCasosNuncaDejanHuecosNiMezclanGrupos() {
    Random azar = new Random(2026);
    for (int caso = 0; caso < 2000; caso++) {
      int n = 2 + azar.nextInt(13);
      List<Long> rango = LongStream.rangeClosed(1, n).boxed().toList();
      Set<Long> buffs = new java.util.HashSet<>();
      for (long c = 1; c <= n; c++) {
        if (azar.nextInt(5) == 0) {
          buffs.add(c);
        }
      }
      int k = 1 + azar.nextInt(Math.min(6, n));
      List<String> grupos = new ArrayList<>();
      List<RepartoFrecuencia.Ruta> rutas = new ArrayList<>();
      long id = 1;
      for (int g = 0; g < k; g++) {
        grupos.add("G" + g);
        int cuantas = 1 + azar.nextInt(8);
        for (int r = 0; r < cuantas; r++) {
          String bultos = BigDecimal.valueOf(azar.nextInt(90_000), 2).toPlainString();
          rutas.add(ruta(id++, "G" + g, bultos, 1 + azar.nextInt(n + 3)));
        }
      }
      RepartoFrecuencia.Plan plan = RepartoFrecuencia.planificar(rango, buffs, grupos, rutas);
      sinHuecosYUnGrupoPorChute(rango, rutas, plan);
    }
  }

  @Test
  void rangoUsadoTomaElBloqueConMasBuff() {
    List<Long> rango = List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L);

    // Los tres bloques de 10 tienen los dos buff: queda el primero (el 28 y el 29 sobran).
    assertEquals(
        List.of(15L, 16L, 17L, 18L, 22L, 23L, 24L, 25L, 26L, 27L),
        RepartoFrecuencia.rangoUsado(rango, Set.of(18L, 25L), 10));
    // De 2 chutes, ningún bloque junta los dos buff: el primero con uno es 17-18.
    assertEquals(List.of(17L, 18L), RepartoFrecuencia.rangoUsado(rango, Set.of(18L, 25L), 2));
    assertEquals(rango, RepartoFrecuencia.rangoUsado(rango, Set.of(18L, 25L), 20));
  }

  private static String grupoDe(RepartoFrecuencia.Plan plan, List<RepartoFrecuencia.Ruta> rutas, Long chute) {
    return rutas.stream().filter((r) -> chute.equals(plan.destino().get(r.id()))).findFirst().orElseThrow().grupo();
  }
}
