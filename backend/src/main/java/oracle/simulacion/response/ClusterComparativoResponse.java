package oracle.simulacion.response;

import java.math.BigDecimal;

public record ClusterComparativoResponse(
    Long id,
    String cluster,
    BigDecimal tiempoOriginal,
    BigDecimal tiempoBalanceado,
    BigDecimal limiteHoras,
    boolean excede,
    int rutasOriginal,
    int rutasBalanceado,
    /** Rutas que llegaron de otro cluster y las que se fueron a otro (cambio manual). */
    int rutasEntraron,
    int rutasSalieron,
    BigDecimal unidadesOriginal,
    BigDecimal unidadesBalanceado,
    BigDecimal bultosOriginal,
    BigDecimal bultosBalanceado,
    BigDecimal lineasOriginal,
    BigDecimal lineasBalanceado,
    int codOriginal,
    int codBalanceado) {}
