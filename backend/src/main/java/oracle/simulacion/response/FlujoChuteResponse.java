package oracle.simulacion.response;

/** Operarios de un chute en una ola y en la siguiente. */
public record FlujoChuteResponse(Long chuteId, String chute, int antes, int despues) {}
