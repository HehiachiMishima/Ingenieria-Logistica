package oracle.simulacion.response;

import java.math.BigDecimal;
import java.util.List;

public record ChuteComparativoResponse(
    Long id,
    String chute,
    /** Base del usuario. */
    int operariosOriginal,
    /** En la vista de un cluster, los de su ola; en la vista final, el máximo entre sus olas. */
    int operariosBalanceado,
    int rutasOriginal,
    int rutasBalanceado,
    /** Rutas que el balanceo sacó del chute y las que trajo de otros. */
    int rutasSalieron,
    int rutasLlegaron,
    BigDecimal tiempoOriginal,
    BigDecimal tiempoBalanceado,
    String estado,
    BigDecimal bultosOriginal,
    BigDecimal bultosBalanceado,
    BigDecimal unidadesOriginal,
    BigDecimal unidadesBalanceado,
    BigDecimal lineasOriginal,
    BigDecimal lineasBalanceado,
    int codOriginal,
    int codBalanceado,
    /** Clusters que el chute atiende antes o después del balanceo (para filtrar por cluster). */
    List<String> clusters,
    /** Clusters del chute al cargar el Excel y tras el balanceo, por separado. */
    List<String> clustersOriginal,
    List<String> clustersBalanceado,
    /** El chute junta CL1 y CL2 (no permitido) al cargar el Excel / tras el balanceo. */
    boolean mezclaOriginal,
    boolean mezclaBalanceada,
    /** Clusters de CL1/CL2 que salieron del chute para dejar de compartirlo. */
    List<String> clustersSeparados) {}
