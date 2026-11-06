package oracle.simulacion.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Cluster al que pasa una ruta (antes o después de balancear, si ninguno de los dos está cerrado)
 * y, opcional, el chute al que va en el mismo paso: así se valida el destino final y no un estado
 * intermedio.
 */
public record CambiarClusterRequest(@NotNull @Positive Long clusterId, @Positive Long chuteId) {}
