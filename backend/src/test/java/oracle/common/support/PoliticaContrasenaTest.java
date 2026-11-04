package oracle.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PoliticaContrasenaTest {

    @Test
    void exigeDiezCaracteresYComoMaximoLos72BytesDeBcrypt() {
        assertThat(PoliticaContrasena.cumple("corta-123")).isFalse();
        assertThat(PoliticaContrasena.cumple("suficiente1")).isTrue();
        assertThat(PoliticaContrasena.cumple("a".repeat(72))).isTrue();
        assertThat(PoliticaContrasena.cumple("a".repeat(73))).isFalse();
        // 40 caracteres con tilde = 80 bytes en UTF-8: BCrypt los truncaria.
        assertThat(PoliticaContrasena.cumple("ñ".repeat(40))).isFalse();
        assertThat(PoliticaContrasena.cumple("          ")).isFalse();
        assertThat(PoliticaContrasena.cumple(null)).isFalse();
    }
}
