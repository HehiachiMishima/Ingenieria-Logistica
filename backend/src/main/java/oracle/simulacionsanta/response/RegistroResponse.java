package oracle.simulacionsanta.response;

import java.time.Instant;
import java.util.List;
import oracle.simulacionsanta.model.EstadoRegistro;

/** Una carga de Santa Anita con sus totales. */
public record RegistroResponse(
    Long id,
    String nombre,
    String nombreArchivo,
    EstadoRegistro estado,
    int envioActual,
    /** Filas válidas guardadas (una paleta cada una). */
    int paletas,
    /** Rutas distintas. */
    int rutas,
    /** Filas con error que no se guardaron. */
    int filasOmitidas,
    /** Suma de los 3 envíos. */
    int bultos,
    List<Integer> bultosEnvios,
    /** Paletas de C1, C2 y C3. */
    List<Integer> paletasCluster,
    Long creadoPor,
    Instant creadoEn,
    Long actualizadoPor,
    Instant actualizadoEn) {}
