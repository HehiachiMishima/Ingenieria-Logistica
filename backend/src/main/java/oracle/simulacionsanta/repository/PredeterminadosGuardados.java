package oracle.simulacionsanta.repository;

import java.time.Instant;
import java.util.List;

/**
 * La fila de simulacion_santa.predeterminados y sus ubicaciones.
 *
 * @param ordenClusters nombres de OrdenLlenado de C1, C2 y C3
 * @param actualizadoPor null mientras sean los de fábrica
 * @param bloqueado operarios y bultos por hora bloqueados para todos los registros
 */
public record PredeterminadosGuardados(
    int operarios,
    int bultosHora,
    List<String> ordenClusters,
    Long actualizadoPor,
    Instant actualizadoEn,
    boolean bloqueado,
    List<UbicacionGuardada> ubicaciones) {}
