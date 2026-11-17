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
     * Candado global: true = la configuración completa está bloqueada para todos los registros
     * (ninguno la cambia hasta desbloquearla).
     */
    boolean bloqueado,
    /** Nombre visible de quien bloqueó; null si está abierta o el usuario no se encuentra. */
    String bloqueadoPor,
    /** Instante (UTC) del bloqueo; null si está abierta. */
    Instant bloqueadoEn) {}
