package oracle.simulacion.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Cluster al que pasa una ruta (solo antes de balancear ambos clusters). */
public record CambiarClusterRequest(@NotNull @Positive Long clusterId) {}
