package oracle.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import oracle.admin.model.Usuario;
import oracle.admin.repository.UsuarioRepository;
import oracle.common.config.Transacciones;
import oracle.common.exception.NoAutorizadoException;
import oracle.common.security.SesionesRefresh;
import oracle.common.security.SesionesRefresh.Fila;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class SesionRefreshServiceTest {

    private static final String TOKEN = "token-del-navegador";

    private final SesionesRefresh sesiones = mock(SesionesRefresh.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private SesionRefreshService servicio;

    @BeforeEach
    void preparar() {
        Transacciones transacciones = new Transacciones(mock(PlatformTransactionManager.class));
        servicio = new SesionRefreshService(sesiones, usuarios, transacciones, 7200, 43200);
        Usuario activo = new Usuario();
        activo.setActivo(true);
        when(usuarios.findById(7L)).thenReturn(Optional.of(activo));
    }

    @Test
    void renovarRotaElTokenYDevuelveUnoNuevo() {
        Fila fila = new Fila(1L, 7L, "fam", false, false);
        when(sesiones.bloquear(SesionRefreshService.hash(TOKEN))).thenReturn(Optional.of(fila));
        when(sesiones.segundosRestantes(anyString())).thenReturn(7200L);

        SesionRefreshService.Renovado renovado = servicio.renovar(TOKEN, "1.1.1.1");

        assertEquals(7L, renovado.usuarioId());
        assertNotEquals(TOKEN, renovado.refresh().token());
        verify(sesiones).rotar(eq(fila), eq(SesionRefreshService.hash(renovado.refresh().token())), eq(7200L),
                eq("1.1.1.1"));
    }

    @Test
    void unTokenYaUsadoCierraTodaSuFamilia() {
        when(sesiones.bloquear(anyString())).thenReturn(Optional.of(new Fila(1L, 7L, "fam", true, false)));

        assertThrows(NoAutorizadoException.class, () -> servicio.renovar(TOKEN, "1.1.1.1"));

        verify(sesiones).revocarFamilia("fam");
        verify(sesiones, never()).rotar(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void unTokenVencidoNoRenueva() {
        when(sesiones.bloquear(anyString())).thenReturn(Optional.of(new Fila(1L, 7L, "fam", false, true)));

        assertThrows(NoAutorizadoException.class, () -> servicio.renovar(TOKEN, "1.1.1.1"));

        verify(sesiones, never()).rotar(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void unaCuentaDesactivadaNoRenuevaYPierdeLaSesion() {
        Usuario inactivo = new Usuario();
        inactivo.setActivo(false);
        when(usuarios.findById(8L)).thenReturn(Optional.of(inactivo));
        when(sesiones.bloquear(anyString())).thenReturn(Optional.of(new Fila(1L, 8L, "fam", false, false)));

        assertThrows(NoAutorizadoException.class, () -> servicio.renovar(TOKEN, "1.1.1.1"));

        verify(sesiones).revocarFamilia("fam");
    }

    @Test
    void sinTokenOTokenDesconocidoEs401() {
        assertThrows(NoAutorizadoException.class, () -> servicio.renovar(null, "1.1.1.1"));
        when(sesiones.bloquear(anyString())).thenReturn(Optional.empty());
        assertThrows(NoAutorizadoException.class, () -> servicio.renovar(TOKEN, "1.1.1.1"));
    }

    @Test
    void cambiarLaContrasenaConservaSoloLaSesionDeEsteNavegador() {
        when(sesiones.familiaDe(SesionRefreshService.hash(TOKEN))).thenReturn(Optional.of("propia"));

        servicio.cerrarOtras(7L, TOKEN);
        servicio.cerrarOtras(7L, null);

        verify(sesiones).revocarUsuarioExcepto(7L, "propia");
        verify(sesiones).revocarUsuario(7L);
    }

    @Test
    void elHashNoEsElToken() {
        String hash = SesionRefreshService.hash(TOKEN);
        assertEquals(64, hash.length());
        assertNotEquals(TOKEN, hash);
    }
}
