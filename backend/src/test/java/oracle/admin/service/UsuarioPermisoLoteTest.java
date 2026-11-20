package oracle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import oracle.admin.repository.UsuarioPermisoLoteRepository;
import oracle.admin.request.UsuarioPermisosLoteRequest;
import oracle.admin.response.ExcepcionPermisoResponse;
import oracle.admin.response.PermisoDelUsuarioResponse;
import oracle.common.exception.OperacionNoPermitidaException;
import oracle.common.exception.RecursoNoEncontradoException;
import oracle.common.exception.SolicitudInvalidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Tablero de permisos individuales (excepciones) y cambios en lote, contra PostgreSQL real. */
@SpringBootTest
@WithMockUser(authorities = {"VER_USUARIOS", "VER_PERMISOS", "ASIGNAR_PERMISOS"})
@Transactional
class UsuarioPermisoLoteTest {

    @Autowired UsuarioPermisoService servicio;
    @Autowired UsuarioPermisoLoteRepository lote;
    @Autowired JdbcTemplate jdbc;

    private long usuario;
    private long verRoles;
    private long verUsuarios;
    private long crearUsuarios;
    private long exportarMalla;

    @BeforeEach
    void preparar() {
        verRoles = permiso("VER_ROLES");
        verUsuarios = permiso("VER_USUARIOS");
        crearUsuarios = permiso("CREAR_USUARIOS");
        exportarMalla = permiso("EXPORTAR_MALLA");
        usuario = crearUsuario();
        asignar(usuario, crearRol(true, "VER_ROLES", "PANEL_ADMIN"));
        asignar(usuario, crearRol(false, "EXPORTAR_MALLA")); // rol inactivo: no concede
        jdbc.update("""
                INSERT INTO seguridad.usuarios_permisos (usuario_id, permiso_id, otorgado, creado_por, actualizado_por)
                VALUES (?, ?, TRUE, 0, 0)
                """, usuario, crearUsuarios);
    }

    @Test
    void elTableroTraeLoQueDanLosRolesYLasExcepciones() {
        var tablero = servicio.tablero(usuario).datos();
        long catalogo = jdbc.queryForObject("SELECT count(*) FROM seguridad.permisos", Long.class);
        assertThat(tablero.bloqueo()).isNull();
        assertThat(tablero.permisos()).hasSize((int) catalogo);
        assertThat(tablero.permisos()).extracting(PermisoDelUsuarioResponse::nombre).isSorted();

        assertThat(fila(tablero.permisos(), verRoles).porRol()).isTrue();
        assertThat(fila(tablero.permisos(), verRoles).excepcion()).isNull();
        assertThat(fila(tablero.permisos(), exportarMalla).porRol()).isFalse();
        assertThat(fila(tablero.permisos(), crearUsuarios).excepcion()).isTrue();
        assertThat(fila(tablero.permisos(), verUsuarios).otorgable()).isTrue(); // el actor de la prueba es el sistema
    }

    @Test
    void soloSeConcedeLoQueTieneElActor() {
        long actor = crearUsuario();
        asignar(actor, crearRol(true, "PANEL_BITACORA"));
        var tablero = lote.tablero(usuario, actor);
        assertThat(fila(tablero, permiso("PANEL_BITACORA")).otorgable()).isTrue();
        assertThat(fila(tablero, verUsuarios).otorgable()).isFalse();
    }

    @Test
    void elLoteConcedeDeniegaYHeredaEnUnaSolaTransaccionYEsIdempotente() {
        var resultado = servicio.actualizarLote(usuario, new UsuarioPermisosLoteRequest(
                List.of(verUsuarios), List.of(verRoles), List.of(crearUsuarios))).datos();
        assertThat(resultado.concedidos()).isEqualTo(1);
        assertThat(resultado.denegados()).isEqualTo(1);
        assertThat(resultado.heredados()).isEqualTo(1);
        assertThat(resultado.excepciones()).containsExactlyInAnyOrder(
                new ExcepcionPermisoResponse(verRoles, false), new ExcepcionPermisoResponse(verUsuarios, true));

        var reintento = servicio.actualizarLote(usuario, new UsuarioPermisosLoteRequest(
                List.of(verUsuarios), List.of(verRoles), List.of(crearUsuarios))).datos();
        assertThat(reintento.concedidos() + reintento.denegados() + reintento.heredados()).isZero();

        // Pasar de denegado a concedido cambia la fila existente.
        var cambio = servicio.actualizarLote(usuario,
                new UsuarioPermisosLoteRequest(List.of(verRoles), null, null)).datos();
        assertThat(cambio.concedidos()).isEqualTo(1);
        assertThat(lote.excepciones(usuario)).isEqualTo(Map.of(verRoles, true, verUsuarios, true));
    }

    @Test
    void unPermisoInexistenteRevierteTodoElLote() {
        assertThatThrownBy(() -> servicio.actualizarLote(usuario,
                new UsuarioPermisosLoteRequest(List.of(verUsuarios, Long.MAX_VALUE), null, List.of(crearUsuarios))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining(String.valueOf(Long.MAX_VALUE));
        assertThat(lote.excepciones(usuario)).isEqualTo(Map.of(crearUsuarios, true));
    }

    @Test
    void validaLaPeticionYProtegeAlUsuarioProtegido() {
        assertThatThrownBy(() -> servicio.actualizarLote(usuario, new UsuarioPermisosLoteRequest(null, null, null)))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.actualizarLote(usuario,
                new UsuarioPermisosLoteRequest(List.of(verRoles), List.of(verRoles), null)))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.actualizarLote(usuario,
                new UsuarioPermisosLoteRequest(null, List.of(verRoles), List.of(verRoles))))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.tablero(Long.MAX_VALUE)).isInstanceOf(RecursoNoEncontradoException.class);

        Long protegido = jdbc.query("SELECT id FROM seguridad.usuarios WHERE usuario = '@admin'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (protegido != null) {
            assertThat(servicio.tablero(protegido).datos().bloqueo()).contains("protegido");
            assertThatThrownBy(() -> servicio.actualizarLote(protegido,
                    new UsuarioPermisosLoteRequest(null, List.of(verRoles), null)))
                    .isInstanceOf(OperacionNoPermitidaException.class);
        }
    }

    private long crearUsuario() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                INSERT INTO seguridad.usuarios (usuario, contrasena, nombre, apellido, email, creado_por, actualizado_por)
                VALUES (?, 'x', 'Prueba', 'Excepciones', ?, 0, 0) RETURNING id
                """, Long.class, "@prueba_" + sufijo, "prueba_" + sufijo + "@example.test");
    }

    private long crearRol(boolean activo, String... permisos) {
        long id = jdbc.queryForObject("""
                INSERT INTO seguridad.roles (nombre, descripcion, activo, creado_por, actualizado_por)
                VALUES (?, 'Prueba de excepciones', ?, 0, 0) RETURNING id
                """, Long.class, "PRUEBA_" + UUID.randomUUID().toString().substring(0, 8), activo);
        for (String permiso : permisos) {
            jdbc.update("""
                    INSERT INTO seguridad.roles_permisos (rol_id, permiso_id, creado_por)
                    SELECT ?, id, 0 FROM seguridad.permisos WHERE nombre = ?
                    """, id, permiso);
        }
        return id;
    }

    private void asignar(long usuarioId, long rolId) {
        jdbc.update("INSERT INTO seguridad.usuarios_roles (usuario_id, rol_id, creado_por) VALUES (?, ?, 0)",
                usuarioId, rolId);
    }

    private long permiso(String nombre) {
        return jdbc.queryForObject("SELECT id FROM seguridad.permisos WHERE nombre = ?", Long.class, nombre);
    }

    private static PermisoDelUsuarioResponse fila(List<PermisoDelUsuarioResponse> permisos, long id) {
        return permisos.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }
}
