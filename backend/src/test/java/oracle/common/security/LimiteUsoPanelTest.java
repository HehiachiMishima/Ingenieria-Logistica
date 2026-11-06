package oracle.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import oracle.common.exception.LimiteExcedidoException;
import oracle.common.security.LimiteUsoPanel.Categoria;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LimiteUsoPanelTest {

    private static final LimiteUsoPanel.Cupos CUPOS = new LimiteUsoPanel.Cupos(3, 2, 1, 1);

    @Test
    void clasificaPorMetodoYRuta() {
        assertThat(LimiteUsoPanel.categoria("GET", "/api/incidencias")).isEqualTo(Categoria.LECTURA);
        assertThat(LimiteUsoPanel.categoria("GET", "/api/incidencias/exportar")).isEqualTo(Categoria.EXPORTACION);
        assertThat(LimiteUsoPanel.categoria("GET", "/api/incidencias/analitica"))
                .isEqualTo(Categoria.ANALITICA);
        assertThat(LimiteUsoPanel.categoria("GET", "/api/usuarios/resumen")).isEqualTo(Categoria.ANALITICA);
        assertThat(LimiteUsoPanel.categoria("POST", "/api/incidencias")).isEqualTo(Categoria.ESCRITURA);
        assertThat(LimiteUsoPanel.categoria("DELETE", "/api/incidencias/exportar")).isEqualTo(Categoria.ESCRITURA);
    }

    @Test
    void alPasarseRespondeLimiteConRetryAfter() {
        LimiteUsoPanel interceptor = new LimiteUsoPanel(new LimiteSolicitudes(), "prueba", CUPOS);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(peticion("POST", "/api/x"), respuesta, null)).isTrue();
        assertThat(interceptor.preHandle(peticion("POST", "/api/x"), respuesta, null)).isTrue();

        assertThatThrownBy(() -> interceptor.preHandle(peticion("POST", "/api/x"), respuesta, null))
                .isInstanceOf(LimiteExcedidoException.class);
        assertThat(Long.parseLong(respuesta.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
    }

    @Test
    void cadaCategoriaTieneSuPropioCubo() {
        LimiteUsoPanel interceptor = new LimiteUsoPanel(new LimiteSolicitudes(), "prueba", CUPOS);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        interceptor.preHandle(peticion("GET", "/api/x/exportar"), respuesta, null);
        assertThatThrownBy(() -> interceptor.preHandle(peticion("GET", "/api/x/exportar"), respuesta, null))
                .isInstanceOf(LimiteExcedidoException.class);

        // Agotar las exportaciones no frena los listados.
        assertThat(interceptor.preHandle(peticion("GET", "/api/x"), respuesta, null)).isTrue();
    }

    private static MockHttpServletRequest peticion(String metodo, String ruta) {
        return new MockHttpServletRequest(metodo, ruta);
    }
}
