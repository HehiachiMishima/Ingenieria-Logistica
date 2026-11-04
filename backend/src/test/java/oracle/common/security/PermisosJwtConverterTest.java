package oracle.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PermisosJwtConverterTest {

    private static final Instant CAMBIO = Instant.parse("2026-11-04T10:00:05.700Z");

    @Test
    void unTokenEmitidoAntesDelCambioDeContrasenaYaNoSirve() {
        assertThat(PermisosJwtConverter.emitidoAntesDe(Instant.parse("2026-11-04T09:59:00Z"), CAMBIO)).isTrue();
        assertThat(PermisosJwtConverter.emitidoAntesDe(null, CAMBIO)).isTrue();
    }

    @Test
    void unTokenDelMismoSegundoODespuesSigueValiendo() {
        // iat trae segundos enteros: el emitido justo despues del cambio cae en el mismo segundo.
        assertThat(PermisosJwtConverter.emitidoAntesDe(Instant.parse("2026-11-04T10:00:05Z"), CAMBIO)).isFalse();
        assertThat(PermisosJwtConverter.emitidoAntesDe(Instant.parse("2026-11-04T10:01:00Z"), CAMBIO)).isFalse();
        assertThat(PermisosJwtConverter.emitidoAntesDe(Instant.parse("2026-11-04T09:00:00Z"), null)).isFalse();
    }
}
