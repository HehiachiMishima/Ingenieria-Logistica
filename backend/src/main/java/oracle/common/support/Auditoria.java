package oracle.common.support;

import java.time.Instant;
import oracle.common.model.Auditable;

/**
 * Auditoria manual: el proyecto no tiene @EnableJpaAuditing (la capa config
 * esta fuera de alcance), asi que el service fija fechas e id del actor
 * antes de guardar. Ver columnas creado_por/actualizado_por del esquema.
 * El actor se resuelve en el hilo de la peticion (ActorActual) y se pasa
 * aqui; 0 = sistema/seeding, convencion del esquema.
 */
public final class Auditoria {

    public static void alCrear(Auditable entidad, Long actor) {
        Instant ahora = Instant.now();
        entidad.setCreadoEn(ahora);
        entidad.setActualizadoEn(ahora);
        entidad.setCreadoPor(actor);
        entidad.setActualizadoPor(actor);
    }

    public static void alActualizar(Auditable entidad, Long actor) {
        entidad.setActualizadoEn(Instant.now());
        entidad.setActualizadoPor(actor);
    }

    private Auditoria() {
    }
}
