package oracle.simulacion.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Varias rutas a una misma celda de la matriz (cluster y chute) en un solo paso: todas se mueven o
 * ninguna. Las que ya están en ese cluster y chute se dejan como están.
 */
public record MoverRutasRequest(
    @NotEmpty @Size(max = MoverRutasRequest.MAXIMO) List<@NotNull @Positive Long> rutaIds,
    @NotNull @Positive Long clusterId,
    @NotNull @Positive Long chuteId) {

  /** Tope por petición: una selección a mano no llega a tanto y acota la transacción. */
  public static final int MAXIMO = 500;
}
