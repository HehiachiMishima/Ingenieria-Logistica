package oracle.simulacion.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Balanceo interno de una sola frecuencia (Diarias, L-X-V o M-J-S): solo mira sus rutas, así que
 * no choca con las otras. Corre en orden y ningún paso deshace al anterior:
 *
 * <ol>
 *   <li><b>Por grupos.</b> Cada grupo recibe chutes según sus bultos ({@link TramosGrupos}), nunca
 *       más que sus rutas, y se pasa un chute de un grupo a otro mientras las columnas del día
 *       queden más parejas. Dentro de cada grupo sus rutas se reparten en sus columnas y se nivelan:
 *       se mueve o se cambia una ruta mientras la suma de cuadrados de la carga baje (termina sola).
 *   <li><b>Buff estratégico.</b> Se eligen el orden de los grupos y dónde empieza el bloque moviendo
 *       columnas completas, así que no cambia ningún bulto del paso 1. Gana el orden en que el grupo
 *       con más bultos tiene un buff con su columna más pesada; después, el que pone más bultos en
 *       los buff; después, el que deja más rutas donde ya estaban.
 *   <li><b>Verificación.</b> Los chutes usados quedan seguidos (los que sobran, en los extremos), un
 *       grupo por chute, cada grupo en chutes seguidos y ningún chute usado sin rutas.
 * </ol>
 *
 * <p>Todo es en memoria y determinista: el mismo dato da siempre el mismo resultado.
 */
final class RepartoFrecuencia {

  /** Una ruta de la frecuencia: su grupo, sus bultos y el chute donde está hoy. */
  record Ruta(Long id, String grupo, BigDecimal bultos, Long chute) {}

  /**
   * Resultado del balanceo.
   *
   * @param destino chute final de cada ruta (por id)
   * @param tramos tramo de cada grupo, en el orden del rango
   * @param usados chutes usados, seguidos
   * @param buffs chute con buff usado → grupo que lo tiene
   * @param carga bultos de cada chute usado
   * @param mayor grupo con más bultos (null sin rutas)
   * @param tope true si la nivelación llegó a su máximo de pasos (red de seguridad)
   */
  record Plan(
      Map<Long, Long> destino,
      List<TramosGrupos.Tramo> tramos,
      List<Long> usados,
      Map<Long, String> buffs,
      Map<Long, BigDecimal> carga,
      String mayor,
      boolean tope) {}

  /** Órdenes de grupos que se prueban como máximo al elegir el buff (con 6 grupos son 720 por inicio). */
  static final long PRESUPUESTO_ORDENES = 200_000;

  private static final int PASOS_MINIMOS = 1000;
  private static final BigDecimal DOS = BigDecimal.valueOf(2);
  private static final Comparator<Ruta> MAS_PESADA =
      Comparator.comparing(Ruta::bultos).reversed().thenComparing(Ruta::id);

  private RepartoFrecuencia() {}

  /**
   * Chutes del rango que usarán las frecuencias: si ninguna tiene rutas para todos, se toma el
   * bloque seguido de {@code necesarios} chutes con más buff (a igualdad, el primero).
   */
  static List<Long> rangoUsado(List<Long> rango, Set<Long> buffs, int necesarios) {
    if (necesarios <= 0 || necesarios >= rango.size()) {
      return List.copyOf(rango);
    }
    int mejor = 0;
    int mejorBuffs = -1;
    for (int inicio = 0; inicio + necesarios <= rango.size(); inicio++) {
      int conBuff = 0;
      for (Long chute : rango.subList(inicio, inicio + necesarios)) {
        if (buffs.contains(chute)) {
          conBuff++;
        }
      }
      if (conBuff > mejorBuffs) {
        mejorBuffs = conBuff;
        mejor = inicio;
      }
    }
    return List.copyOf(rango.subList(mejor, mejor + necesarios));
  }

  /**
   * Balancea las rutas de una frecuencia en el rango.
   *
   * @param rango chutes útiles en orden (sin bloqueados)
   * @param buffs chutes con buff
   * @param ordenGrupos grupos en orden natural (los que no tienen rutas se ignoran)
   * @param rutas rutas de la frecuencia
   * @throws IllegalArgumentException si hay más grupos con rutas que chutes
   */
  static Plan planificar(List<Long> rango, Set<Long> buffs, List<String> ordenGrupos, List<Ruta> rutas) {
    Map<String, List<Ruta>> porGrupo = new LinkedHashMap<>();
    for (String g : ordenGrupos) {
      porGrupo.put(g, new ArrayList<>());
    }
    for (Ruta ruta : rutas) {
      porGrupo.computeIfAbsent(ruta.grupo(), (g) -> new ArrayList<>()).add(ruta);
    }
    porGrupo.values().removeIf(List::isEmpty);
    porGrupo.values().forEach((lista) -> lista.sort(MAS_PESADA));
    List<String> grupos = List.copyOf(porGrupo.keySet());
    int k = grupos.size();
    int n = rango.size();
    if (k == 0) {
      return new Plan(Map.of(), List.of(), List.of(), Map.of(), Map.of(), null, false);
    }
    if (k > n) {
      throw new IllegalArgumentException(k + " grupos no caben en " + n + " chutes.");
    }
    int total = rutas.size();
    int usar = Math.min(n, total);
    Map<Long, Integer> posicion = new HashMap<>();
    for (int i = 0; i < n; i++) {
      posicion.put(rango.get(i), i);
    }

    // ---------- 1. Por grupos ----------
    int[] chutes = cantidades(rango, grupos, porGrupo, usar);
    boolean tope = false;
    List<List<Columna>> columnas = new ArrayList<>(k);
    for (int g = 0; g < k; g++) {
      List<Ruta> suyas = porGrupo.get(grupos.get(g));
      int maximo = Math.max(PASOS_MINIMOS, 4 * suyas.size());
      List<Columna> frio = enFrio(suyas, chutes[g]);
      boolean topeFrio = nivelar(frio, maximo);
      List<Columna> caliente = enCaliente(suyas, chutes[g], posicion);
      boolean topeCaliente = nivelar(caliente, maximo);
      // Primero lo más parejo; a igualdad, el reparto que parte de como está hoy (mueve menos).
      boolean usaFrio = sumaCuadrados(frio).compareTo(sumaCuadrados(caliente)) < 0;
      columnas.add(usaFrio ? frio : caliente);
      tope |= usaFrio ? topeFrio : topeCaliente;
    }

    // ---------- 2. Buff estratégico ----------
    BigDecimal[] totales = new BigDecimal[k];
    int mayor = 0;
    for (int g = 0; g < k; g++) {
      totales[g] = columnas.get(g).stream().map((c) -> c.carga).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (totales[g].compareTo(totales[mayor]) > 0) {
        mayor = g;
      }
    }
    Ubicacion ubicacion = ubicar(rango, buffs, columnas, chutes, usar, mayor);

    // ---------- 3. Verificación ----------
    Map<Long, Long> destino = new LinkedHashMap<>();
    Map<Long, BigDecimal> carga = new LinkedHashMap<>();
    Map<Long, String> deBuff = new LinkedHashMap<>();
    List<TramosGrupos.Tramo> tramos = new ArrayList<>();
    int pos = ubicacion.inicio();
    int prioridad = 1;
    for (int g : ubicacion.orden()) {
      List<Long> tramo = List.copyOf(rango.subList(pos, pos + chutes[g]));
      Long[] asignado = asignar(columnas.get(g), tramo, buffs);
      for (int c = 0; c < asignado.length; c++) {
        Columna columna = columnas.get(g).get(c);
        for (Ruta ruta : columna.todas()) {
          destino.put(ruta.id(), asignado[c]);
        }
        carga.put(asignado[c], columna.carga);
        if (buffs.contains(asignado[c])) {
          deBuff.put(asignado[c], grupos.get(g));
        }
      }
      tramos.add(
          new TramosGrupos.Tramo(grupos.get(g), prioridad++, porGrupo.get(grupos.get(g)).size(), totales[g], tramo));
      pos += chutes[g];
    }
    List<Long> usados = List.copyOf(rango.subList(ubicacion.inicio(), ubicacion.inicio() + usar));
    verificar(posicion, rutas, destino, usados);
    return new Plan(destino, List.copyOf(tramos), usados, deBuff, carga, grupos.get(mayor), tope);
  }

  // ------------------------------------------------------------------
  // Paso 1: cuántos chutes por grupo y sus columnas
  // ------------------------------------------------------------------

  /**
   * Chutes de cada grupo: el reparto proporcional a sus bultos y, después, pasar un chute de un
   * grupo a otro mientras las columnas queden más parejas (menor suma de cuadrados; a igualdad, menor
   * diferencia entre la más cargada y la más liviana). Cada paso mejora, así que termina.
   */
  private static int[] cantidades(List<Long> rango, List<String> grupos, Map<String, List<Ruta>> porGrupo, int usar) {
    int k = grupos.size();
    List<TramosGrupos.Grupo> entrada = new ArrayList<>(k);
    int[] tope = new int[k];
    for (int g = 0; g < k; g++) {
      List<Ruta> suyas = porGrupo.get(grupos.get(g));
      tope[g] = suyas.size();
      BigDecimal bultos = suyas.stream().map(Ruta::bultos).reduce(BigDecimal.ZERO, BigDecimal::add);
      entrada.add(new TramosGrupos.Grupo(grupos.get(g), suyas.size(), bultos, suyas.size()));
    }
    int[] c = new int[k];
    List<TramosGrupos.Tramo> base = TramosGrupos.repartir(rango.subList(0, usar), entrada).tramos();
    for (int g = 0; g < k; g++) {
      c[g] = base.get(g).chutes().size();
    }
    if (k == 1) {
      return c;
    }
    Map<Long, Resumen> cache = new HashMap<>();
    Resumen actual = resumen(c, grupos, porGrupo, cache);
    while (true) {
      int[] mejor = null;
      Resumen mejorResumen = actual;
      for (int a = 0; a < k; a++) {
        for (int b = 0; b < k; b++) {
          if (a == b || c[a] <= 1 || c[b] >= tope[b]) {
            continue;
          }
          int[] v = c.clone();
          v[a]--;
          v[b]++;
          Resumen r = resumen(v, grupos, porGrupo, cache);
          if (r.compareTo(mejorResumen) < 0) {
            mejorResumen = r;
            mejor = v;
          }
        }
      }
      if (mejor == null) {
        return c;
      }
      c = mejor;
      actual = mejorResumen;
    }
  }

  /** Suma de cuadrados y extremos de las columnas de todos los grupos con esas cantidades. */
  private static Resumen resumen(int[] c, List<String> grupos, Map<String, List<Ruta>> porGrupo, Map<Long, Resumen> cache) {
    Resumen total = null;
    for (int g = 0; g < c.length; g++) {
      int grupo = g;
      int cantidad = c[g];
      Resumen parcial =
          cache.computeIfAbsent(
              ((long) grupo << 32) | cantidad,
              (clave) -> {
                List<Ruta> suyas = porGrupo.get(grupos.get(grupo));
                List<Columna> cols = enFrio(suyas, cantidad);
                nivelar(cols, Math.max(PASOS_MINIMOS, 4 * suyas.size()));
                return Resumen.de(cols);
              });
      total = total == null ? parcial : total.sumar(parcial);
    }
    return total;
  }

  /** Reparto desde cero: de la más pesada a la más liviana, cada ruta a la columna con menos carga. */
  private static List<Columna> enFrio(List<Ruta> rutas, int cantidad) {
    List<Columna> cols = new ArrayList<>(cantidad);
    for (int i = 0; i < cantidad; i++) {
      cols.add(new Columna());
    }
    for (Ruta ruta : rutas) {
      menosCargada(cols).poner(ruta);
    }
    return cols;
  }

  /**
   * Reparto que parte de como está hoy: el grupo conserva sus chutes del rango con más carga (hasta
   * su cantidad), las demás rutas entran de la más pesada a la columna con menos carga, y una
   * columna que sigue vacía recibe una ruta de la más cargada.
   */
  private static List<Columna> enCaliente(List<Ruta> rutas, int cantidad, Map<Long, Integer> posicion) {
    Map<Long, Columna> porChute = new LinkedHashMap<>();
    for (Ruta ruta : rutas) {
      if (ruta.chute() != null && posicion.containsKey(ruta.chute())) {
        porChute.computeIfAbsent(ruta.chute(), (c) -> new Columna()).poner(ruta);
      }
    }
    List<Map.Entry<Long, Columna>> hoy = new ArrayList<>(porChute.entrySet());
    hoy.sort(
        Comparator.comparing((Map.Entry<Long, Columna> e) -> e.getValue().carga)
            .reversed()
            .thenComparing((e) -> posicion.get(e.getKey())));
    List<Columna> cols = new ArrayList<>(cantidad);
    List<Ruta> sueltas = new ArrayList<>();
    for (int i = 0; i < hoy.size(); i++) {
      if (i < cantidad) {
        cols.add(hoy.get(i).getValue());
      } else {
        sueltas.addAll(hoy.get(i).getValue().todas());
      }
    }
    for (Ruta ruta : rutas) {
      if (ruta.chute() == null || !posicion.containsKey(ruta.chute())) {
        sueltas.add(ruta);
      }
    }
    while (cols.size() < cantidad) {
      cols.add(new Columna());
    }
    sueltas.sort(MAS_PESADA);
    for (Ruta ruta : sueltas) {
      menosCargada(cols).poner(ruta);
    }
    // Columnas vacías (más chutes que los que el grupo usa hoy y nada suelto): una ruta de la
    // columna más cargada que tenga más de una, la más cercana a la mitad de su carga.
    for (Columna vacia : cols) {
      if (vacia.cantidad > 0) {
        continue;
      }
      Columna donante = null;
      for (Columna c : cols) {
        if (c.cantidad > 1 && (donante == null || c.carga.compareTo(donante.carga) > 0)) {
          donante = c;
        }
      }
      if (donante == null) {
        break;
      }
      Ruta ruta = cercana(donante, donante.carga.divide(DOS), null);
      if (ruta == null) {
        ruta = donante.todas().getLast();
      }
      donante.quitar(ruta);
      vacia.poner(ruta);
    }
    return cols;
  }

  private static Columna menosCargada(List<Columna> cols) {
    Columna mejor = null;
    for (Columna c : cols) {
      if (c.cantidad == 0) {
        return c;
      }
      if (mejor == null || c.carga.compareTo(mejor.carga) < 0) {
        mejor = c;
      }
    }
    return mejor;
  }

  /**
   * Nivela las columnas de un grupo: mueve una ruta de una columna más cargada a una más liviana si
   * pesa menos que la diferencia (la más cercana a la mitad), y si ninguna sirve, cambia una por otra
   * más liviana. Cada paso baja la suma de cuadrados, así que termina; nunca deja una columna sin
   * rutas. Devuelve true si llegó al máximo de pasos.
   */
  private static boolean nivelar(List<Columna> cols, int maximo) {
    for (int paso = 0; paso < maximo; paso++) {
      if (!mover(cols) && !cambiar(cols)) {
        return false;
      }
    }
    return true;
  }

  private static boolean mover(List<Columna> cols) {
    List<Columna> cargadas = ordenadas(cols, true);
    List<Columna> livianas = ordenadas(cols, false);
    for (Columna desde : cargadas) {
      if (desde.cantidad < 2) {
        continue;
      }
      for (Columna hacia : livianas) {
        BigDecimal diferencia = desde.carga.subtract(hacia.carga);
        if (diferencia.signum() <= 0) {
          break;
        }
        Ruta ruta = cercana(desde, diferencia.divide(DOS), diferencia);
        if (ruta != null) {
          desde.quitar(ruta);
          hacia.poner(ruta);
          return true;
        }
      }
    }
    return false;
  }

  private static boolean cambiar(List<Columna> cols) {
    List<Columna> cargadas = ordenadas(cols, true);
    List<Columna> livianas = ordenadas(cols, false);
    for (Columna desde : cargadas) {
      for (Columna hacia : livianas) {
        BigDecimal diferencia = desde.carga.subtract(hacia.carga);
        if (diferencia.signum() <= 0) {
          break;
        }
        BigDecimal mitad = diferencia.divide(DOS);
        BigDecimal mejorA = null;
        BigDecimal mejorB = null;
        BigDecimal mejorDistancia = null;
        for (BigDecimal a : desde.rutas.keySet()) {
          BigDecimal ideal = a.subtract(mitad);
          for (BigDecimal b : new BigDecimal[] {hacia.rutas.floorKey(ideal), hacia.rutas.ceilingKey(ideal)}) {
            // 0 < a − b < diferencia: el cambio acerca las dos columnas.
            if (b == null || b.compareTo(a) >= 0 || b.compareTo(a.subtract(diferencia)) <= 0) {
              continue;
            }
            BigDecimal distancia = a.subtract(b).subtract(mitad).abs();
            if (mejorDistancia == null || distancia.compareTo(mejorDistancia) < 0) {
              mejorA = a;
              mejorB = b;
              mejorDistancia = distancia;
            }
          }
        }
        if (mejorA != null) {
          Ruta pesada = desde.rutas.get(mejorA).peekFirst();
          Ruta liviana = hacia.rutas.get(mejorB).peekFirst();
          desde.quitar(pesada);
          hacia.quitar(liviana);
          desde.poner(liviana);
          hacia.poner(pesada);
          return true;
        }
      }
    }
    return false;
  }

  /** La ruta con 0 < bultos < limite (sin límite si es null) más cercana a {@code ideal}. */
  private static Ruta cercana(Columna columna, BigDecimal ideal, BigDecimal limite) {
    BigDecimal mejor = null;
    for (BigDecimal b : new BigDecimal[] {columna.rutas.floorKey(ideal), columna.rutas.ceilingKey(ideal)}) {
      if (b == null || b.signum() <= 0 || (limite != null && b.compareTo(limite) >= 0)) {
        continue;
      }
      if (mejor == null || b.subtract(ideal).abs().compareTo(mejor.subtract(ideal).abs()) < 0) {
        mejor = b;
      }
    }
    if (mejor == null && limite != null) {
      // Lo más cercano por debajo o por encima no sirvió: la mayor que aún pesa menos que el límite.
      BigDecimal debajo = columna.rutas.lowerKey(limite);
      if (debajo != null && debajo.signum() > 0) {
        mejor = debajo;
      }
    }
    return mejor == null ? null : columna.rutas.get(mejor).peekFirst();
  }

  private static List<Columna> ordenadas(List<Columna> cols, boolean cargadasPrimero) {
    List<Columna> copia = new ArrayList<>(cols);
    Comparator<Columna> porCarga = Comparator.comparing((Columna c) -> c.carga);
    copia.sort(cargadasPrimero ? porCarga.reversed() : porCarga);
    return copia;
  }

  private static BigDecimal sumaCuadrados(List<Columna> cols) {
    BigDecimal suma = BigDecimal.ZERO;
    for (Columna c : cols) {
      suma = suma.add(c.carga.multiply(c.carga));
    }
    return suma;
  }

  // ------------------------------------------------------------------
  // Paso 2: orden de los grupos y buff
  // ------------------------------------------------------------------

  private record Ubicacion(int[] orden, int inicio) {}

  /** Estado de la búsqueda del mejor orden (solo cambia de lugar columnas completas). */
  private static final class Busqueda {
    List<Long> rango;
    Set<Long> buffs;
    List<List<Columna>> columnas;
    int[] chutes;
    int mayor;
    int[] buffHasta;
    BigDecimal[][] pesadasPrimero;
    long hojas;
    int[] mejorOrden;
    int mejorInicio;
    boolean mejorMayor;
    BigDecimal mejorBuff;
    int mejorQuedan = -1;
  }

  private static Ubicacion ubicar(List<Long> rango, Set<Long> buffs, List<List<Columna>> columnas, int[] chutes, int usar, int mayor) {
    int k = columnas.size();
    Busqueda s = new Busqueda();
    s.rango = rango;
    s.buffs = buffs;
    s.columnas = columnas;
    s.chutes = chutes;
    s.mayor = mayor;
    s.buffHasta = new int[rango.size() + 1];
    for (int i = 0; i < rango.size(); i++) {
      s.buffHasta[i + 1] = s.buffHasta[i] + (buffs.contains(rango.get(i)) ? 1 : 0);
    }
    // Bultos acumulados de las columnas de cada grupo, de la más cargada a la más liviana.
    s.pesadasPrimero = new BigDecimal[k][];
    for (int g = 0; g < k; g++) {
      List<BigDecimal> cargas = columnas.get(g).stream().map((c) -> c.carga).sorted(Comparator.reverseOrder()).toList();
      s.pesadasPrimero[g] = new BigDecimal[cargas.size() + 1];
      s.pesadasPrimero[g][0] = BigDecimal.ZERO;
      for (int i = 0; i < cargas.size(); i++) {
        s.pesadasPrimero[g][i + 1] = s.pesadasPrimero[g][i].add(cargas.get(i));
      }
    }
    for (int inicio = 0; inicio + usar <= rango.size() && s.hojas < PRESUPUESTO_ORDENES; inicio++) {
      probar(s, new int[k], new boolean[k], 0, inicio, inicio);
    }
    return new Ubicacion(s.mejorOrden, s.mejorInicio);
  }

  private static void probar(Busqueda s, int[] orden, boolean[] usado, int i, int pos, int inicio) {
    int k = orden.length;
    if (s.hojas >= PRESUPUESTO_ORDENES) {
      return;
    }
    if (i < k) {
      for (int g = 0; g < k; g++) {
        if (!usado[g]) {
          usado[g] = true;
          orden[i] = g;
          probar(s, orden, usado, i + 1, pos + s.chutes[g], inicio);
          usado[g] = false;
        }
      }
      return;
    }
    s.hojas++;
    boolean mayorConBuff = false;
    BigDecimal enBuff = BigDecimal.ZERO;
    int p = inicio;
    for (int g : orden) {
      int conBuff = s.buffHasta[p + s.chutes[g]] - s.buffHasta[p];
      enBuff = enBuff.add(s.pesadasPrimero[g][Math.min(conBuff, s.chutes[g])]);
      if (g == s.mayor) {
        mayorConBuff = conBuff > 0;
      }
      p += s.chutes[g];
    }
    int comparacion = 0;
    if (s.mejorOrden == null) {
      comparacion = 1;
    } else if (mayorConBuff != s.mejorMayor) {
      comparacion = mayorConBuff ? 1 : -1;
    } else {
      comparacion = enBuff.compareTo(s.mejorBuff);
    }
    if (comparacion < 0) {
      return;
    }
    int quedan = quedan(s, orden, inicio);
    if (comparacion == 0 && quedan <= s.mejorQuedan) {
      return;
    }
    s.mejorOrden = orden.clone();
    s.mejorInicio = inicio;
    s.mejorMayor = mayorConBuff;
    s.mejorBuff = enBuff;
    s.mejorQuedan = quedan;
  }

  /** Rutas que se quedan en el chute donde están hoy con ese orden e inicio. */
  private static int quedan(Busqueda s, int[] orden, int inicio) {
    int total = 0;
    int p = inicio;
    for (int g : orden) {
      List<Long> tramo = s.rango.subList(p, p + s.chutes[g]);
      Long[] asignado = asignar(s.columnas.get(g), tramo, s.buffs);
      for (int c = 0; c < asignado.length; c++) {
        total += s.columnas.get(g).get(c).enChute(asignado[c]);
      }
      p += s.chutes[g];
    }
    return total;
  }

  /**
   * Chute de cada columna del grupo dentro de su tramo: las columnas más cargadas van a los chutes
   * con buff y las demás a los otros. Dentro de cada parte, cada columna prefiere el chute donde ya
   * están más de sus rutas (mueve menos); las que no tienen ninguno toman los libres en orden.
   */
  private static Long[] asignar(List<Columna> cols, List<Long> tramo, Set<Long> buffs) {
    List<Long> conBuff = tramo.stream().filter(buffs::contains).toList();
    List<Long> sinBuff = tramo.stream().filter((c) -> !buffs.contains(c)).toList();
    // A igual carga, al buff va la que menos rutas tiene hoy en los chutes sin buff del tramo.
    int[] enSinBuff = new int[cols.size()];
    for (int i = 0; i < cols.size(); i++) {
      for (Long chute : sinBuff) {
        enSinBuff[i] += cols.get(i).enChute(chute);
      }
    }
    Integer[] porCarga = new Integer[cols.size()];
    for (int i = 0; i < porCarga.length; i++) {
      porCarga[i] = i;
    }
    java.util.Arrays.sort(
        porCarga,
        Comparator.comparing((Integer i) -> cols.get(i).carga)
            .reversed()
            .thenComparingInt((i) -> enSinBuff[i])
            .thenComparingInt((i) -> i));
    Long[] asignado = new Long[cols.size()];
    int cuantas = Math.min(conBuff.size(), cols.size());
    repartir(cols, java.util.Arrays.asList(porCarga).subList(0, cuantas), conBuff, asignado);
    repartir(cols, java.util.Arrays.asList(porCarga).subList(cuantas, porCarga.length), sinBuff, asignado);
    return asignado;
  }

  private static void repartir(List<Columna> cols, List<Integer> elegidas, List<Long> chutes, Long[] asignado) {
    List<Long> libres = new ArrayList<>(chutes);
    List<Integer> sinLugar = new ArrayList<>();
    for (int c : elegidas) {
      Long mejor = null;
      int mejorCuenta = 0;
      for (Long chute : libres) {
        int cuenta = cols.get(c).enChute(chute);
        if (cuenta > mejorCuenta) {
          mejorCuenta = cuenta;
          mejor = chute;
        }
      }
      if (mejor == null) {
        sinLugar.add(c);
      } else {
        asignado[c] = mejor;
        libres.remove(mejor);
      }
    }
    for (int c : sinLugar) {
      asignado[c] = libres.removeFirst();
    }
  }

  // ------------------------------------------------------------------
  // Paso 3: verificación
  // ------------------------------------------------------------------

  private static void verificar(Map<Long, Integer> posicion, List<Ruta> rutas, Map<Long, Long> destino, List<Long> usados) {
    Map<Long, String> grupoEn = new HashMap<>();
    TreeMap<Integer, Long> ocupadas = new TreeMap<>();
    Map<String, List<Integer>> posicionesGrupo = new HashMap<>();
    for (Ruta ruta : rutas) {
      Long chute = destino.get(ruta.id());
      Integer p = chute == null ? null : posicion.get(chute);
      if (p == null) {
        throw new IllegalStateException("La ruta " + ruta.id() + " quedó fuera del rango.");
      }
      String previo = grupoEn.putIfAbsent(chute, ruta.grupo());
      if (previo != null && !previo.equals(ruta.grupo())) {
        throw new IllegalStateException("El chute " + chute + " quedó con dos grupos.");
      }
      if (ocupadas.put(p, chute) == null) {
        posicionesGrupo.computeIfAbsent(ruta.grupo(), (g) -> new ArrayList<>()).add(p);
      }
    }
    if (!ocupadas.isEmpty()
        && (ocupadas.lastKey() - ocupadas.firstKey() + 1 != ocupadas.size() || ocupadas.size() != usados.size())) {
      throw new IllegalStateException("Quedó un chute vacío entre los usados.");
    }
    posicionesGrupo.forEach(
        (grupo, posiciones) -> {
          int menor = posiciones.stream().mapToInt(Integer::intValue).min().orElseThrow();
          int mayor = posiciones.stream().mapToInt(Integer::intValue).max().orElseThrow();
          if (mayor - menor + 1 != posiciones.size()) {
            throw new IllegalStateException("El grupo " + grupo + " quedó partido.");
          }
        });
  }

  // ------------------------------------------------------------------
  // Estructuras
  // ------------------------------------------------------------------

  /** Rutas de un chute del reparto, ordenadas por bultos (elegir una cuesta log(n)). */
  private static final class Columna {
    final TreeMap<BigDecimal, ArrayDeque<Ruta>> rutas = new TreeMap<>();
    BigDecimal carga = BigDecimal.ZERO;
    int cantidad;
    /** Rutas por chute de hoy; se arma al consultarlo y se descarta al cambiar la columna. */
    private Map<Long, Integer> porChute;

    void poner(Ruta ruta) {
      rutas.computeIfAbsent(ruta.bultos(), (b) -> new ArrayDeque<>()).addLast(ruta);
      carga = carga.add(ruta.bultos());
      cantidad++;
      porChute = null;
    }

    void quitar(Ruta ruta) {
      ArrayDeque<Ruta> mismas = rutas.get(ruta.bultos());
      mismas.remove(ruta);
      if (mismas.isEmpty()) {
        rutas.remove(ruta.bultos());
      }
      carga = carga.subtract(ruta.bultos());
      cantidad--;
      porChute = null;
    }

    List<Ruta> todas() {
      List<Ruta> lista = new ArrayList<>(cantidad);
      rutas.descendingMap().values().forEach(lista::addAll);
      return lista;
    }

    /** Rutas de la columna que hoy están en ese chute. */
    int enChute(Long chute) {
      if (porChute == null) {
        porChute = new HashMap<>();
        for (ArrayDeque<Ruta> mismas : rutas.values()) {
          for (Ruta ruta : mismas) {
            if (ruta.chute() != null) {
              porChute.merge(ruta.chute(), 1, Integer::sum);
            }
          }
        }
      }
      return porChute.getOrDefault(chute, 0);
    }
  }

  /** Suma de cuadrados y extremos de un conjunto de columnas (para comparar repartos). */
  private record Resumen(BigDecimal cuadrados, BigDecimal maxima, BigDecimal minima) implements Comparable<Resumen> {
    static Resumen de(List<Columna> cols) {
      BigDecimal maxima = null;
      BigDecimal minima = null;
      for (Columna c : cols) {
        maxima = maxima == null || c.carga.compareTo(maxima) > 0 ? c.carga : maxima;
        minima = minima == null || c.carga.compareTo(minima) < 0 ? c.carga : minima;
      }
      return new Resumen(sumaCuadrados(cols), maxima, minima);
    }

    Resumen sumar(Resumen otro) {
      return new Resumen(cuadrados.add(otro.cuadrados), maxima.max(otro.maxima), minima.min(otro.minima));
    }

    @Override
    public int compareTo(Resumen otro) {
      int porCuadrados = cuadrados.compareTo(otro.cuadrados);
      return porCuadrados != 0 ? porCuadrados : maxima.subtract(minima).compareTo(otro.maxima.subtract(otro.minima));
    }
  }
}
