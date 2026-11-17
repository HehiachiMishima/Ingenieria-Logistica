package oracle.simulacionsanta.repository;

import java.time.Instant;
import java.util.List;

/**
 * La fila de simulacion_santa.predeterminados y sus ubicaciones.
 *
 * @param ordenClusters nombres de OrdenLlenado de C1, C2 y C3
 * @param actualizadoPor null mientras sean los de fábrica
 * @param candado si la configuración está bloqueada para todos los registros, quién y cuándo
 */
public record PredeterminadosGuardados(
    int operarios,
    int bultosHora,
    List<String> ordenClusters,
    Long actualizadoPor,
    Instant actualizadoEn,
    Candado candado,
    List<UbicacionGuardada> ubicaciones) {}
