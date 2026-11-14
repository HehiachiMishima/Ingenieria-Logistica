package oracle.simulacion.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Arma los tramos de los grupos de un cluster en las diarias, en el orden de las reglas del
 * balanceo automático:
 *
 * <ol>
 *   <li><b>Buff primero.</b> Cada chute con buff del rango, en orden, es del grupo de la ruta más
 *       pesada que aún no tiene buff, de cualquier frecuencia (las diarias cuentan como una más).
 *       Un buff es de un solo grupo, el mismo los dos días: los grupos nunca se mezclan en un chute.
 *   <li><b>Los grupos se agrupan alrededor.</b> El tramo de un grupo dueño de buff los incluye; los
 *       demás grupos ocupan, en orden natural, los chutes de antes y de después.
 *   <li><b>Sin chutes vacíos.</b> Ningún tramo tiene más chutes que rutas en su día con menos rutas
 *       ({@link TramosGrupos.Grupo#tope()}), y los tramos van seguidos: el rango se recorta a lo que
 *       se puede llenar; lo demás queda fuera del rango, no vacío dentro de él.
 * </ol>
 *
 * <p>Si un buff no se puede dar a su grupo sin romper eso (no cabe en su tope o quedaría entre
 * buff de otro grupo), queda como un chute más y se avisa. Las cuentas son exactas: el mismo
 * cluster y el mismo rango dan siempre el mismo plan.
 */
final class PlanTramos {

  /** Una ruta del cluster para elegir el dueño de los buff: su grupo y sus bultos. */
  record Pesada(String grupo, BigDecimal bultos) {}

  /**
   * Tramos en el orden en que quedan en el rango; el rango recortado a ellos (seguidos); los
   * chutes del rango pedido que quedan fuera; la ruta que le dio cada buff a su grupo (en el orden
   * del rango); los buff sin dueño.
   */
  record Plan(
      List<TramosGrupos.Tramo> tramos,
      List<Long> rango,
      int sinUsar,
      Map<Long, Pesada> duenos,
      List<Long> sinDueno) {}

  private PlanTramos() {}

  /**
   * @param rango chutes útiles del rango pedido, en orden (sin bloqueados)
   * @param grupos grupos del cluster en orden natural, con su tope de chutes
   * @param buffs chutes con buff del cluster (se usan los que están en el rango)
   * @param pesadas rutas del cluster de la más pesada a la más liviana
   */
  static Plan planificar(
      List<Long> rango,
      List<TramosGrupos.Grupo> grupos,
      Set<Long> buffs,
      List<Pesada> pesadas) {
    return planificar(rango, grupos, buffs, pesadas, Map.of(), false);
  }

  /**
   * Como {@link #planificar(List, List, Set, List)}, para un día (puede dejar chutes libres entre
   * tramos, fuera de ese día): {@code fijos} son los chutes con
   * diarias (ya ubicadas, no se mueven) y el grupo de esas diarias. Su grupo los incluye en su
   * tramo y ningún otro grupo los usa; un buff con diarias ya es de ese grupo. Las {@code pesadas}
   * son solo las rutas que el día mueve.
   *
   * @throws IllegalArgumentException si las diarias de dos grupos quedaron intercaladas (p. ej.
   *     movidas a mano): no hay tramos seguidos posibles
   */
  static Plan planificar(
      List<Long> rango,
      List<TramosGrupos.Grupo> grupos,
      Set<Long> buffs,
      List<Pesada> pesadas,
      Map<Long, String> fijos) {
    return planificar(rango, grupos, buffs, pesadas, fijos, true);
  }

  /**
   * {@code porDia}: en un día pueden quedar chutes libres entre tramos (fuera de ese día) para que
   * cada buff tenga su ruta más pesada; en las diarias no, porque el rango vale para los dos días.
   */
  private static Plan planificar(
      List<Long> rango,
      List<TramosGrupos.Grupo> grupos,
      Set<Long> buffs,
      List<Pesada> pesadas,
      Map<Long, String> fijos,
      boolean porDia) {
    // Un grupo con diarias necesita al menos los chutes que van de su primera a su última diaria.
    Map<String, int[]> fijosPorGrupo = new LinkedHashMap<>();
    for (int i = 0; i < rango.size(); i++) {
      String g = fijos.get(rango.get(i));
      if (g != null) {
        int indice = i;
        fijosPorGrupo.merge(g, new int[] {i, i}, (a, b) -> new int[] {a[0], indice});
      }
    }
    List<TramosGrupos.Grupo> conTope =
        grupos.stream()
            .map(
                (g) -> {
                  int[] ext = fijosPorGrupo.get(g.nombre());
                  long minimo = ext == null ? 0 : ext[1] - ext[0] + 1;
                  return minimo > g.tope()
                      ? new TramosGrupos.Grupo(g.nombre(), g.rutas(), g.bultos(), minimo)
                      : g;
                })
            .toList();
    Map<String, Integer> tamano = new LinkedHashMap<>();
    TramosGrupos.repartir(rango, conTope).tramos().forEach((t) -> tamano.put(t.grupo(), t.chutes().size()));
    Map<String, TramosGrupos.Grupo> porNombre = new LinkedHashMap<>();
    conTope.forEach((g) -> porNombre.put(g.nombre(), g));

    Map<Long, String> duenos = new LinkedHashMap<>();
    rango.stream().filter(fijos::containsKey).forEach((chute) -> duenos.put(chute, fijos.get(chute)));
    Map<Long, Pesada> ganadoras = new LinkedHashMap<>();
    List<Long> sinDueno = new ArrayList<>();
    Disposicion disposicion = disponer(rango, porNombre, tamano, duenos, porDia);
    if (disposicion == null) {
      throw new IllegalArgumentException("Las diarias de dos grupos quedaron intercaladas.");
    }
    int siguiente = 0;
    for (Long buff : rango) {
      // Un buff con diarias ya es del grupo de esas diarias.
      if (!buffs.contains(buff) || duenos.containsKey(buff)) {
        continue;
      }
      if (siguiente >= pesadas.size()) {
        sinDueno.add(buff);
        continue;
      }
      String grupo = pesadas.get(siguiente).grupo();
      duenos.put(buff, grupo);
      Disposicion conEste = disponer(rango, porNombre, tamano, duenos, porDia);
      if (conEste == null) {
        // Su grupo no lo alcanza sin dejar vacíos ni mezclar: queda como un chute más.
        duenos.remove(buff);
        sinDueno.add(buff);
      } else {
        disposicion = conEste;
        ganadoras.put(buff, pesadas.get(siguiente));
        siguiente++;
      }
    }
    return new Plan(
        disposicion.tramos(),
        disposicion.rango(),
        rango.size() - disposicion.rango().size(),
        java.util.Collections.unmodifiableMap(ganadoras),
        List.copyOf(sinDueno));
  }

  private record Disposicion(List<TramosGrupos.Tramo> tramos, List<Long> rango) {}

  /**
   * Ubica los tramos seguidos en el rango: los grupos dueños de buff (en el orden de sus buff)
   * cubriendo sus buff, y los demás antes y después en orden natural. Entre las ubicaciones
   * posibles, la que empieza más cerca del inicio del rango. Null si no hay ninguna.
   */
  private static Disposicion disponer(
      List<Long> rango,
      Map<String, TramosGrupos.Grupo> grupos,
      Map<String, Integer> tamanoBase,
      Map<Long, String> duenos,
      boolean permitirHuecos) {
    int n = rango.size();
    // Índices del primer y último ancla (buff o diaria fija) de cada grupo.
    Map<String, int[]> extremos = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      String dueno = duenos.get(rango.get(i));
      if (dueno != null) {
        int indice = i;
        extremos.merge(dueno, new int[] {i, i}, (a, b) -> new int[] {a[0], indice});
      }
    }
    // Un grupo dueño no puede tener buff de otro grupo entre los suyos.
    List<String> duenosEnOrden = new ArrayList<>(extremos.keySet());
    for (int k = 1; k < duenosEnOrden.size(); k++) {
      if (extremos.get(duenosEnOrden.get(k - 1))[1] > extremos.get(duenosEnOrden.get(k))[0]) {
        return null;
      }
    }
    Map<String, Integer> tamano = new HashMap<>(tamanoBase);
    for (Map.Entry<String, int[]> e : extremos.entrySet()) {
      int tramo = e.getValue()[1] - e.getValue()[0] + 1;
      if (tramo > grupos.get(e.getKey()).tope()) {
        return null;
      }
      tamano.merge(e.getKey(), tramo, Math::max);
    }
    // Si cubrir un buff agrandó un tramo y ya no entra todo, se achica a los demás.
    int total = tamano.values().stream().mapToInt(Integer::intValue).sum();
    while (total > n) {
      String elegido = null;
      for (String g : grupos.keySet()) {
        int minimo = extremos.containsKey(g) ? extremos.get(g)[1] - extremos.get(g)[0] + 1 : 1;
        if (tamano.get(g) > minimo && (elegido == null || tamano.get(g) > tamano.get(elegido))) {
          elegido = g;
        }
      }
      if (elegido == null) {
        return null;
      }
      tamano.merge(elegido, -1, Integer::sum);
      total--;
    }
    List<String> libres = grupos.keySet().stream().filter((g) -> !extremos.containsKey(g)).toList();
    int k = duenosEnOrden.size();
    // Cada grupo sin ancla va antes, entre o después de los dueños sin cambiar su orden natural:
    // se prueban todas las combinaciones (son pocas) y gana la de menos chutes libres entre
    // tramos, luego la que empieza antes y luego la que deja más grupos en su orden.
    Ubicacion mejor = null;
    for (int[] huecos : combinaciones(libres.size(), k)) {
      List<String> secuencia = new ArrayList<>();
      for (int hueco = 0; hueco <= k; hueco++) {
        for (int i = 0; i < libres.size(); i++) {
          if (huecos[i] == hueco) {
            secuencia.add(libres.get(i));
          }
        }
        if (hueco < k) {
          secuencia.add(duenosEnOrden.get(hueco));
        }
      }
      Ubicacion ubicacion = ubicar(secuencia, n, grupos, tamano, extremos);
      if (ubicacion == null || (!permitirHuecos && ubicacion.huecos() > 0)) {
        continue;
      }
      int orden = java.util.Arrays.stream(huecos).sum();
      if (mejor == null
          || ubicacion.huecos() < mejor.huecos()
          || (ubicacion.huecos() == mejor.huecos() && ubicacion.inicio()[0] < mejor.inicio()[0])
          || (ubicacion.huecos() == mejor.huecos()
              && ubicacion.inicio()[0] == mejor.inicio()[0]
              && orden < mejor.orden())) {
        mejor = new Ubicacion(ubicacion.secuencia(), ubicacion.inicio(), ubicacion.largo(), ubicacion.huecos(), orden);
      }
    }
    if (mejor == null) {
      return null;
    }
    List<TramosGrupos.Tramo> tramos = new ArrayList<>();
    List<Long> usados = new ArrayList<>();
    for (int j = 0; j < mejor.secuencia().size(); j++) {
      TramosGrupos.Grupo grupo = grupos.get(mejor.secuencia().get(j));
      List<Long> chutes = List.copyOf(rango.subList(mejor.inicio()[j], mejor.inicio()[j] + mejor.largo()[j]));
      tramos.add(new TramosGrupos.Tramo(grupo.nombre(), j + 1, grupo.rutas(), grupo.bultos(), chutes));
      usados.addAll(chutes);
    }
    return new Disposicion(List.copyOf(tramos), List.copyOf(usados));
  }

  /** Tramos de una secuencia: dónde empieza y cuántos chutes tiene cada uno, y chutes libres entre ellos. */
  private record Ubicacion(List<String> secuencia, int[] inicio, int[] largo, int huecos, int orden) {}

  /**
   * Ubica la secuencia en el rango: primero lo antes posible (cada dueño cubriendo sus anclas),
   * luego empuja cada tramo hacia el siguiente para no dejar chutes libres entre ellos y, si aún
   * quedan, agranda los vecinos hasta su tope. Null si la secuencia no cabe.
   */
  private static Ubicacion ubicar(
      List<String> secuencia,
      int n,
      Map<String, TramosGrupos.Grupo> grupos,
      Map<String, Integer> tamano,
      Map<String, int[]> extremos) {
    int m = secuencia.size();
    int[] inicio = new int[m];
    int[] largo = new int[m];
    int posicion = 0;
    for (int j = 0; j < m; j++) {
      String g = secuencia.get(j);
      largo[j] = tamano.get(g);
      int[] ext = extremos.get(g);
      int desde = ext == null ? posicion : Math.max(posicion, ext[1] - largo[j] + 1);
      if ((ext != null && desde > ext[0]) || desde + largo[j] > n) {
        return null;
      }
      inicio[j] = desde;
      posicion = desde + largo[j];
    }
    for (int j = m - 2; j >= 0; j--) {
      int[] ext = extremos.get(secuencia.get(j));
      int tope = inicio[j + 1] - largo[j];
      if (ext != null) {
        tope = Math.min(tope, ext[0]);
      }
      inicio[j] = Math.max(inicio[j], tope);
    }
    int huecos = 0;
    for (int j = 0; j + 1 < m; j++) {
      int libre = inicio[j + 1] - (inicio[j] + largo[j]);
      int crece = (int) Math.min(libre, grupos.get(secuencia.get(j)).tope() - largo[j]);
      if (crece > 0) {
        largo[j] += crece;
        libre -= crece;
      }
      int creceSiguiente = (int) Math.min(libre, grupos.get(secuencia.get(j + 1)).tope() - largo[j + 1]);
      if (creceSiguiente > 0) {
        inicio[j + 1] -= creceSiguiente;
        largo[j + 1] += creceSiguiente;
        libre -= creceSiguiente;
      }
      huecos += Math.max(0, libre);
    }
    return new Ubicacion(List.copyOf(secuencia), inicio, largo, huecos, 0);
  }

  /** Todas las formas de repartir {@code m} grupos (en orden) en {@code k + 1} lugares. */
  private static List<int[]> combinaciones(int m, int k) {
    List<int[]> todas = new ArrayList<>();
    combinar(new int[m], 0, 0, k, todas);
    return todas;
  }

  private static void combinar(int[] actual, int i, int minimo, int k, List<int[]> todas) {
    if (i == actual.length) {
      todas.add(actual.clone());
      return;
    }
    for (int lugar = minimo; lugar <= k; lugar++) {
      actual[i] = lugar;
      combinar(actual, i + 1, lugar, k, todas);
    }
  }
}
