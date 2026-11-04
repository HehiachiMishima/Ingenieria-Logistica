package oracle.simulacion.request;

import jakarta.validation.constraints.NotNull;

public record MoverRutaRequest(@NotNull Long chuteId) {}
