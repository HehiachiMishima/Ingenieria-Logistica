package oracle.simulacion.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reparte los chutes útiles de un rango entre los grupos de un cluster: cada
 * grupo recibe un tramo de chutes seguidos, en el orden de su prioridad (el
 * primero, los primeros chutes del rango).
 *
 * <p>Cuántos chutes recibe cada grupo depende de sus bultos: su parte del rango
 * es {@code chutes × bultos del grupo / bultos del cluster}. Cada grupo recibe
 * la parte entera, con un mínimo de 1 y nunca más chutes que rutas (un chute
 * sin ruta no aporta). Si sobran chutes, van uno a uno al grupo con la mayor
 * fracción pendiente; si faltan (por los mínimos), se quitan al que más se pasó
 * de su parte. En un empate decide la prioridad: el de mayor prioridad recibe
 * primero y pierde último.
 *
 * <p>Las cuentas son exactas (BigDecimal, sin redondeos): el mismo cluster y el
 * mismo rango dan siempre el mismo reparto.
 */
final class TramosGrupos {

  /** Un grupo del cluster con su carga (rutas ≥ 1). */
  record Grupo(String nombre, long rutas, BigDecimal bultos) {}

  /** Tramo de un grupo: sus chutes en el orden del rango; prioridad desde 1. */
  record Tramo(String grupo, int prioridad, long rutas, BigDecimal bultos, List<Long> chutes) {}

  /** Tramos en orden de prioridad y chutes del rango que no recibió ningún grupo. */
  record Reparto(List<Tramo> tramos, int sinUsar) {}

  private TramosGrupos() {}

  /**
   * @param rango chutes útiles del rango, en orden (sin bloqueados)
   * @param enPrioridad grupos del cluster, del de mayor prioridad al de menor
   * @throws IllegalArgumentException si hay más grupos que chutes (el servicio lo valida antes)
   */
  static Reparto repartir(List<Long> rango, List<Grupo> enPrioridad) {
    int n = rango.size();
    int k = enPrioridad.size();
    if (k == 0) {
      return new Reparto(List.of(), n);
    }
    if (n < k) {
      throw new IllegalArgumentException(k + " grupos no caben en " + n + " chutes.");
    }
    BigDecimal[] peso = new BigDecimal[k];
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < k; i++) {
      BigDecimal bultos = enPrioridad.get(i).bultos();
      peso[i] = bultos == null || bultos.signum() < 0 ? BigDecimal.ZERO : bultos;
      total = total.add(peso[i]);
    }
    if (total.signum() == 0) {
      // Sin bultos no hay carga que comparar: todos pesan igual.
      for (int i = 0; i < k; i++) {
        peso[i] = BigDecimal.ONE;
      }
      total = BigDecimal.valueOf(k);
    }
    // parte_i = n × peso_i / total. Se compara el numerador n × peso_i con
    // cupo × total para no dividir (y no redondear).
    BigDecimal chutes = BigDecimal.valueOf(n);
    BigDecimal[] numerador = new BigDecimal[k];
    int[] cupo = new int[k];
    int suma = 0;
    for (int i = 0; i < k; i++) {
      numerador[i] = chutes.multiply(peso[i]);
      long entera = numerador[i].divideToIntegralValue(total).longValue();
      cupo[i] = (int) Math.min(Math.max(1L, entera), maximo(enPrioridad.get(i), n));
      suma += cupo[i];
    }
    // Los mínimos de 1 pueden pasarse del rango: se quita al que más excede su parte.
    while (suma > n) {
      int elegido = -1;
      BigDecimal mayor = null;
      for (int i = k - 1; i >= 0; i--) {
        if (cupo[i] > 1) {
          BigDecimal exceso = total.multiply(BigDecimal.valueOf(cupo[i])).subtract(numerador[i]);
          if (mayor == null || exceso.compareTo(mayor) > 0) {
            mayor = exceso;
            elegido = i;
          }
        }
      }
      cupo[elegido]--;
      suma--;
    }
    // Chutes sobrantes: al que más fracción le falta, sin pasar sus rutas.
    while (suma < n) {
      int elegido = -1;
      BigDecimal mayor = null;
      for (int i = 0; i < k; i++) {
        if (cupo[i] < maximo(enPrioridad.get(i), n)) {
          BigDecimal falta = numerador[i].subtract(total.multiply(BigDecimal.valueOf(cupo[i])));
          if (mayor == null || falta.compareTo(mayor) > 0) {
            mayor = falta;
            elegido = i;
          }
        }
      }
      if (elegido < 0) {
        break;
      }
      cupo[elegido]++;
      suma++;
    }
    List<Tramo> tramos = new ArrayList<>(k);
    int desde = 0;
    for (int i = 0; i < k; i++) {
      Grupo grupo = enPrioridad.get(i);
      tramos.add(
          new Tramo(
              grupo.nombre(),
              i + 1,
              grupo.rutas(),
              grupo.bultos(),
              List.copyOf(rango.subList(desde, desde + cupo[i]))));
      desde += cupo[i];
    }
    return new Reparto(List.copyOf(tramos), n - suma);
  }

  /** Un grupo no recibe más chutes que rutas tiene (ni más que el rango). */
  private static long maximo(Grupo grupo, int n) {
    return Math.max(1L, Math.min(grupo.rutas(), n));
  }
}
