package oracle.simulacionsanta.response;

import java.time.Instant;
import java.util.List;
import oracle.simulacionsanta.model.OrdenLlenado;

/** Configuración de un registro o, con registroId null, los predeterminados. */
public record ConfiguracionResponse(
    Long registroId,
    int operarios,
    int bultosHora,
    List<OrdenLlenado> ordenClusters,
    /** 15 a 33 en orden. */
    List<RampaConfig> rampas,
    /** 1 a 24 en orden. */
    List<PasilloConfig> pasillos,
    Long actualizadoPor,
    Instant actualizadoEn,
    /**
     * Candado global: true = operarios y bultos por hora bloqueados para todos los registros
     * (clusters, posiciones y orden de llenado siguen editables en cada registro).
     */
    boolean bloqueado) {}
