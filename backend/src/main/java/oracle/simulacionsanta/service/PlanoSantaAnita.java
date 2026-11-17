package oracle.simulacionsanta.service;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Plano físico del Cross Santa Anita (PDF «Cross Santa Anita»). Es una constante: el mismo para
 * todos los registros; la base de datos lo repite solo en sus CHECK.
 *
 * <ul>
 *   <li>Rampas 15 a 33, cada una con un rack doble sobre la línea con la puerta siguiente: 10
 *       posiciones (5 filas × 2 lados) en 15, 16, 17, 18 y 25; 14 (7 × 2) en las demás.
 *   <li>Pasillos 1 a 24, con 24 posiciones cada uno (12 filas × 2 lados).
 * </ul>
 */
public final class PlanoSantaAnita {

  public static final int PRIMERA_RAMPA = 15;
  public static final int ULTIMA_RAMPA = 33;
  public static final int PRIMER_PASILLO = 1;
  public static final int ULTIMO_PASILLO = 24;
  public static final int TOTAL_RAMPAS = ULTIMA_RAMPA - PRIMERA_RAMPA + 1;
  public static final int TOTAL_PASILLOS = ULTIMO_PASILLO - PRIMER_PASILLO + 1;

  /** Capacidad de las rampas 15, 16, 17, 18 y 25 (5 filas × 2 lados). */
  public static final int CAPACIDAD_RAMPA_CORTA = 10;
  /** Capacidad de las demás rampas (7 filas × 2 lados). */
  public static final int CAPACIDAD_RAMPA_LARGA = 14;
  public static final int FILAS_PASILLO = 12;
  public static final int POSICIONES_PASILLO = FILAS_PASILLO * 2;

  public static final int CLUSTERS = 3;

  private static final Set<Integer> RAMPAS_CORTAS = Set.of(15, 16, 17, 18, 25);
  private static final List<Integer> RAMPAS =
      IntStream.rangeClosed(PRIMERA_RAMPA, ULTIMA_RAMPA).boxed().toList();
  private static final List<Integer> PASILLOS =
      IntStream.rangeClosed(PRIMER_PASILLO, ULTIMO_PASILLO).boxed().toList();

  private PlanoSantaAnita() {}

  public static boolean esRampa(int numero) {
    return numero >= PRIMERA_RAMPA && numero <= ULTIMA_RAMPA;
  }

  public static boolean esPasillo(int numero) {
    return numero >= PRIMER_PASILLO && numero <= ULTIMO_PASILLO;
  }

  /** Posiciones del rack de una rampa (10 o 14). */
  public static int capacidadRampa(int numero) {
    if (!esRampa(numero)) {
      throw new IllegalArgumentException("No existe la rampa " + numero + ".");
    }
    return RAMPAS_CORTAS.contains(numero) ? CAPACIDAD_RAMPA_CORTA : CAPACIDAD_RAMPA_LARGA;
  }

  /** 15 a 33 en orden. */
  public static List<Integer> rampas() {
    return RAMPAS;
  }

  /** 1 a 24 en orden. */
  public static List<Integer> pasillos() {
    return PASILLOS;
  }
}
