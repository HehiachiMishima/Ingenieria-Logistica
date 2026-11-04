package oracle.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import oracle.common.exception.LimiteExcedidoException;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private static final int MAX_POR_USUARIO = 10;
    private static final int MAX_POR_IP = 30;

    @Test
    void bloqueaTrasElLimitePorUsuario() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String clave = LoginRateLimiter.claveUsuario("10.0.0.1", "ana");

        for (int i = 0; i < MAX_POR_USUARIO; i++) {
            limiter.registrarFallo(clave);
        }

        assertThatThrownBy(() -> limiter.verificar(clave))
                .isInstanceOf(LimiteExcedidoException.class);
    }

    @Test
    void limpiarReiniciaElContador() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String clave = LoginRateLimiter.claveUsuario("10.0.0.1", "ana");

        for (int i = 0; i < MAX_POR_USUARIO; i++) {
            limiter.registrarFallo(clave);
        }
        limiter.limpiar(clave);

        assertThatCode(() -> limiter.verificar(clave)).doesNotThrowAnyException();
    }

    @Test
    void elLimitePorIpEsMasHolgadoQueElDeUsuario() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String claveIp = LoginRateLimiter.claveIp("10.0.0.1");

        for (int i = 0; i < MAX_POR_USUARIO; i++) {
            limiter.registrarFallo(claveIp);
        }

        // Con 10 fallos la IP aun no esta bloqueada (su tope es 30).
        assertThatCode(() -> limiter.verificarPorIp("10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void bloqueaLaIpAlSuperarSuTope() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        String claveIp = LoginRateLimiter.claveIp("10.0.0.1");

        for (int i = 0; i < MAX_POR_IP; i++) {
            limiter.registrarFallo(claveIp);
        }

        assertThatThrownBy(() -> limiter.verificarPorIp("10.0.0.1"))
                .isInstanceOf(LimiteExcedidoException.class);
    }

    @Test
    void laReservaCuentaElIntentoAntesDeVerificarLaContrasena() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int i = 0; i < MAX_POR_USUARIO; i++) {
            limiter.reservarLogin("10.0.0.1", "ana");
        }

        assertThatThrownBy(() -> limiter.reservarLogin("10.0.0.1", "ana"))
                .isInstanceOf(LimiteExcedidoException.class);
    }

    @Test
    void elCuboPorCuentaFrenaElAtaqueDesdeMuchasIps() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int i = 0; i < 20; i++) {
            limiter.reservarLogin("10.0.1." + i, "@admin");
        }

        assertThatThrownBy(() -> limiter.reservarLogin("10.0.2.1", "@ADMIN"))
                .isInstanceOf(LimiteExcedidoException.class);
    }

    @Test
    void unLoginCorrectoLiberaUsuarioYCuentaPeroNoLaIp() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        LoginRateLimiter.Reserva reserva = null;
        for (int i = 0; i < MAX_POR_USUARIO; i++) {
            reserva = limiter.reservarLogin("10.0.0.1", "ana");
        }

        limiter.loginCorrecto(reserva);

        assertThatCode(() -> limiter.reservarLogin("10.0.0.1", "ana")).doesNotThrowAnyException();
        for (int i = 0; i < MAX_POR_IP - MAX_POR_USUARIO - 1; i++) {
            limiter.reservarLogin("10.0.0.1", "otro" + i);
        }
        assertThatThrownBy(() -> limiter.verificarPorIp("10.0.0.1")).isInstanceOf(LimiteExcedidoException.class);
    }
}
