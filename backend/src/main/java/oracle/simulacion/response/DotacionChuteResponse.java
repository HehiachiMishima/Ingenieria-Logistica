package oracle.simulacion.response;

import java.util.List;

/** Operarios de un chute en cada ola, en el mismo orden que las olas del dashboard. */
public record DotacionChuteResponse(Long chuteId, String chute, List<Integer> operarios) {}
