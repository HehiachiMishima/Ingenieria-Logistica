package oracle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import oracle.admin.repository.UsuarioRolLoteRepository;
import oracle.admin.request.UsuarioRolesLoteRequest;
import oracle.admin.response.RolTableroResponse;
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

/** Tablero de roles de un usuario y cambios en lote, contra PostgreSQL real (cada prueba revierte). */
@SpringBootTest
@WithMockUser(authorities = {"VER_USUARIOS", "VER_ROLES", "ASIGNAR_ROLES"})
@Transactional
class UsuarioRolLoteTest {

    @Autowired UsuarioRolService servicio;
    @Autowired UsuarioRolLoteRepository lote;
    @Autowired JdbcTemplate jdbc;

    private long usuario;
    private long rolAdmin;
    private long rolBitacora;
    private long rolVacio;

    @BeforeEach
    void preparar() {
        usuario = crearUsuario();
        rolAdmin = crearRol("VER_ROLES", "PANEL_ADMIN");
        rolBitacora = crearRol("PANEL_BITACORA");
        rolVacio = crearRol();
        asignar(usuario, rolAdmin);
    }

    @Test
    void elTableroTraeLosRolesConUsoPanelesYLoAsignado() {
        var tablero = servicio.tablero(usuario).datos();
        long catalogo = jdbc.queryForObject("SELECT count(*) FROM seguridad.roles", Long.class);
        assertThat(tablero.bloqueo()).isNull();
        assertThat(tablero.roles()).hasSize((int) catalogo);
        assertThat(tablero.roles()).extracting(RolTableroResponse::nombre).isSorted();

        var admin = fila(tablero.roles(), rolAdmin);
        assertThat(admin.asignado()).isTrue();
        assertThat(admin.permisos()).isEqualTo(2);
        assertThat(admin.usuarios()).isEqualTo(1);
        assertThat(admin.paneles()).containsExactly("PANEL_ADMIN");
        assertThat(admin.otorgable()).isTrue(); // el actor de la prueba es el sistema
        var vacio = fila(tablero.roles(), rolVacio);
        assertThat(vacio.asignado()).isFalse();
        assertThat(vacio.permisos()).isZero();
        assertThat(vacio.paneles()).isEmpty();
    }

    @Test
    void soloSeOtorganLosRolesCuyosPermisosTieneElActor() {
        long actor = crearUsuario();
        asignar(actor, crearRol("PANEL_BITACORA"));
        var tablero = lote.tablero(usuario, actor);
        assertThat(fila(tablero, rolBitacora).otorgable()).isTrue();
        assertThat(fila(tablero, rolVacio).otorgable()).isTrue();
        assertThat(fila(tablero, rolAdmin).otorgable()).isFalse();
    }

    @Test
    void elLoteAgregaYQuitaEnUnaSolaTransaccionYEsIdempotente() {
        var resultado = servicio.actualizarLote(usuario,
                new UsuarioRolesLoteRequest(List.of(rolBitacora, rolVacio, rolBitacora), List.of(rolAdmin))).datos();
        assertThat(resultado.agregados()).isEqualTo(2);
        assertThat(resultado.quitados()).isEqualTo(1);
        assertThat(resultado.asignados()).containsExactlyInAnyOrder(rolBitacora, rolVacio);

        var reintento = servicio.actualizarLote(usuario,
                new UsuarioRolesLoteRequest(List.of(rolBitacora, rolVacio), List.of(rolAdmin))).datos();
        assertThat(reintento.agregados()).isZero();
        assertThat(reintento.quitados()).isZero();
        assertThat(reintento.asignados()).containsExactlyInAnyOrder(rolBitacora, rolVacio);
    }

    @Test
    void unRolInexistenteRevierteTodoElLote() {
        assertThatThrownBy(() -> servicio.actualizarLote(usuario,
                new UsuarioRolesLoteRequest(List.of(rolBitacora, Long.MAX_VALUE), List.of(rolAdmin))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining(String.valueOf(Long.MAX_VALUE));
        assertThat(lote.asignados(usuario)).containsExactly(rolAdmin);
    }

    @Test
    void validaLaPeticionYProtegeAlUsuarioProtegido() {
        assertThatThrownBy(() -> servicio.actualizarLote(usuario, new UsuarioRolesLoteRequest(null, null)))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.actualizarLote(usuario,
                new UsuarioRolesLoteRequest(List.of(rolVacio), List.of(rolVacio))))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.actualizarLote(Long.MAX_VALUE,
                new UsuarioRolesLoteRequest(List.of(rolVacio), List.of())))
                .isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.tablero(Long.MAX_VALUE)).isInstanceOf(RecursoNoEncontradoException.class);

        Long protegido = jdbc.query("SELECT id FROM seguridad.usuarios WHERE usuario = '@admin'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (protegido != null) {
            assertThat(servicio.tablero(protegido).datos().bloqueo()).contains("protegido");
            assertThatThrownBy(() -> servicio.actualizarLote(protegido,
                    new UsuarioRolesLoteRequest(List.of(rolVacio), List.of())))
                    .isInstanceOf(OperacionNoPermitidaException.class);
        }
    }

    private long crearUsuario() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                INSERT INTO seguridad.usuarios (usuario, contrasena, nombre, apellido, email, creado_por, actualizado_por)
                VALUES (?, 'x', 'Prueba', 'Tablero', ?, 0, 0) RETURNING id
                """, Long.class, "@prueba_" + sufijo, "prueba_" + sufijo + "@example.test");
    }

    private long crearRol(String... permisos) {
        long id = jdbc.queryForObject("""
                INSERT INTO seguridad.roles (nombre, descripcion, creado_por, actualizado_por)
                VALUES (?, 'Prueba de tablero', 0, 0) RETURNING id
                """, Long.class, "PRUEBA_" + UUID.randomUUID().toString().substring(0, 8));
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

    private static RolTableroResponse fila(List<RolTableroResponse> roles, long id) {
        return roles.stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }
}
