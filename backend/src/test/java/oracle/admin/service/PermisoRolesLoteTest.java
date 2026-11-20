package oracle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import oracle.admin.request.PermisoRolesLoteRequest;
import oracle.admin.response.RolDelPermisoResponse;
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

/** «Roles con este permiso»: tablero inverso y cambios en lote, contra PostgreSQL real. */
@SpringBootTest
@WithMockUser(authorities = {"VER_PERMISOS", "VER_ROLES", "ASIGNAR_PERMISOS"})
@Transactional
class PermisoRolesLoteTest {

    @Autowired RolPermisoService servicio;
    @Autowired JdbcTemplate jdbc;

    private long permiso;
    private long rolCon;
    private long rolSin;
    private long rolInactivo;

    @BeforeEach
    void preparar() {
        permiso = jdbc.queryForObject("SELECT id FROM seguridad.permisos WHERE nombre = 'VER_USUARIOS'", Long.class);
        rolCon = crearRol(true);
        rolSin = crearRol(true);
        rolInactivo = crearRol(false);
        dar(rolCon);
        dar(rolInactivo);
        long u1 = crearUsuario();
        long u2 = crearUsuario();
        asignar(u1, rolCon);
        asignar(u2, rolCon);
        asignar(u2, rolInactivo);
        asignar(crearUsuario(), rolInactivo); // solo por un rol inactivo: no cuenta
    }

    @Test
    void elTableroTraeLosRolesYAQuienLlega() {
        var tablero = servicio.tableroRoles(permiso).datos();
        assertThat(tablero.nombre()).isEqualTo("VER_USUARIOS");
        assertThat(tablero.otorgable()).isTrue(); // el actor de la prueba es el sistema
        assertThat(tablero.usuarios()).isEqualTo(alcanceReal());
        assertThat(tablero.roles()).extracting(RolDelPermisoResponse::nombre).isSorted();
        assertThat(fila(tablero.roles(), rolCon).asignado()).isTrue();
        assertThat(fila(tablero.roles(), rolCon).usuarios()).isEqualTo(2);
        assertThat(fila(tablero.roles(), rolSin).asignado()).isFalse();
        assertThat(tablero.roles().stream().filter(RolDelPermisoResponse::protegido))
                .extracting(RolDelPermisoResponse::nombre).containsExactly("ADMINISTRADOR");
    }

    @Test
    void elLoteDaYQuitaEnUnaSolaTransaccionYEsIdempotente() {
        long nuevo = crearUsuario();
        asignar(nuevo, rolSin);
        int antes = alcanceReal();
        var resultado = servicio.actualizarRoles(permiso,
                new PermisoRolesLoteRequest(List.of(rolSin, rolSin), List.of(rolInactivo))).datos();
        assertThat(resultado.agregados()).isEqualTo(1);
        assertThat(resultado.quitados()).isEqualTo(1);
        assertThat(resultado.asignados()).contains(rolCon, rolSin).doesNotContain(rolInactivo);
        assertThat(resultado.usuarios()).isEqualTo(antes + 1).isEqualTo(alcanceReal());

        var reintento = servicio.actualizarRoles(permiso,
                new PermisoRolesLoteRequest(List.of(rolSin), List.of(rolInactivo))).datos();
        assertThat(reintento.agregados() + reintento.quitados()).isZero();
    }

    @Test
    void protegeAlAdministradorYRevierteAnteUnRolInexistente() {
        long administrador = jdbc.queryForObject(
                "SELECT id FROM seguridad.roles WHERE seguridad.es_rol_protegido(nombre)", Long.class);
        assertThatThrownBy(() -> servicio.actualizarRoles(permiso,
                new PermisoRolesLoteRequest(List.of(rolSin), List.of(administrador))))
                .isInstanceOf(OperacionNoPermitidaException.class);
        assertThatThrownBy(() -> servicio.actualizarRoles(permiso,
                new PermisoRolesLoteRequest(List.of(rolSin, Long.MAX_VALUE), null)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining(String.valueOf(Long.MAX_VALUE));
        assertThat(fila(servicio.tableroRoles(permiso).datos().roles(), rolSin).asignado()).isFalse();
    }

    @Test
    void validaLaPeticion() {
        assertThatThrownBy(() -> servicio.actualizarRoles(permiso, new PermisoRolesLoteRequest(null, null)))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.actualizarRoles(permiso,
                new PermisoRolesLoteRequest(List.of(rolSin), List.of(rolSin))))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.tableroRoles(Long.MAX_VALUE)).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.actualizarRoles(Long.MAX_VALUE,
                new PermisoRolesLoteRequest(List.of(rolSin), null)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    private int alcanceReal() {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT ur.usuario_id)
                FROM seguridad.roles_permisos rp
                JOIN seguridad.roles r ON r.id = rp.rol_id AND r.activo
                JOIN seguridad.usuarios_roles ur ON ur.rol_id = rp.rol_id
                WHERE rp.permiso_id = ?
                """, Integer.class, permiso);
    }

    private long crearRol(boolean activo) {
        return jdbc.queryForObject("""
                INSERT INTO seguridad.roles (nombre, descripcion, activo, creado_por, actualizado_por)
                VALUES (?, 'Prueba del tablero inverso', ?, 0, 0) RETURNING id
                """, Long.class, "PRUEBA_" + UUID.randomUUID().toString().substring(0, 8), activo);
    }

    private long crearUsuario() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                INSERT INTO seguridad.usuarios (usuario, contrasena, nombre, apellido, email, creado_por, actualizado_por)
                VALUES (?, 'x', 'Prueba', 'Inverso', ?, 0, 0) RETURNING id
                """, Long.class, "@prueba_" + sufijo, "prueba_" + sufijo + "@example.test");
    }

    private void dar(long rolId) {
        jdbc.update("INSERT INTO seguridad.roles_permisos (rol_id, permiso_id, creado_por) VALUES (?, ?, 0)", rolId, permiso);
    }

    private void asignar(long usuarioId, long rolId) {
        jdbc.update("INSERT INTO seguridad.usuarios_roles (usuario_id, rol_id, creado_por) VALUES (?, ?, 0)",
                usuarioId, rolId);
    }

    private static RolDelPermisoResponse fila(List<RolDelPermisoResponse> roles, long id) {
        return roles.stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }
}
