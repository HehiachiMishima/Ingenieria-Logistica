package oracle.simulacion.response;

import java.math.BigDecimal;

public record ClusterComparativoResponse(
    Long id,
    String cluster,
    BigDecimal tiempoOriginal,
    BigDecimal tiempoBalanceado,
    BigDecimal limiteHoras,
    boolean excede,
    BigDecimal bultosOriginal,
    BigDecimal bultosBalanceado,
    BigDecimal lineasOriginal,
    BigDecimal lineasBalanceado,
    int codOriginal,
    int codBalanceado) {}
