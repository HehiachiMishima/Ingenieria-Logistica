package oracle.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class AutorizacionCacheTest {

    @Mock
    private FuenteAutorizacion fuente;

    private AutorizacionCache cache(boolean habilitado) {
        return new AutorizacionCache(fuente, habilitado, 60_000L, 100L);
    }

    private Optional<FuenteAutorizacion.Identidad> anaActiva(List<String> permisos) {
        return Optional.of(new FuenteAutorizacion.Identidad(true, "ana", permisos, null));
    }

    @Test
    void reutilizaLaEntradaEnLaSegundaConsulta() {
        when(fuente.buscar(1L)).thenReturn(anaActiva(List.of("VER_USUARIOS", "VER_ROLES")));

        AutorizacionCache cache = cache(true);
        AutorizacionCache.Autorizacion primera = cache.cargar(1L);
        AutorizacionCache.Autorizacion segunda = cache.cargar(1L);

        assertThat(primera.autoridades())
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactly("VER_USUARIOS", "VER_ROLES");
        assertThat(segunda.usuario()).isEqualTo("ana");
        verify(fuente, times(1)).buscar(1L);
    }

    @Test
    void invalidarFuerzaUnaNuevaConsulta() {
        when(fuente.buscar(1L)).thenReturn(anaActiva(List.of()));

        AutorizacionCache cache = cache(true);
        cache.cargar(1L);
        cache.invalidar(1L);
        cache.cargar(1L);

        verify(fuente, times(2)).buscar(1L);
    }

    @Test
    void invalidarTodoFuerzaUnaNuevaConsulta() {
        when(fuente.buscar(1L)).thenReturn(anaActiva(List.of("VER_ROLES")), anaActiva(List.of()));

        AutorizacionCache cache = cache(true);
        cache.cargar(1L);
        cache.invalidarTodo();

        assertThat(cache.cargar(1L).autoridades()).isEmpty();
        verify(fuente, times(2)).buscar(1L);
    }

    @Test
    void deshabilitadaConsultaSiempre() {
        when(fuente.buscar(1L)).thenReturn(anaActiva(List.of()));

        AutorizacionCache cache = cache(false);
        cache.cargar(1L);
        cache.cargar(1L);

        verify(fuente, times(2)).buscar(1L);
    }

    @Test
    void usuarioInexistenteLanzaCredencialesInvalidas() {
        when(fuente.buscar(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cache(true).cargar(9L))
                .isInstanceOf(BadCredentialsException.class);
    }
}
