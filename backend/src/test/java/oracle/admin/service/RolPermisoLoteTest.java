package oracle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import oracle.admin.request.RolPermisosLoteRequest;
import oracle.admin.response.PermisoTableroResponse;
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

/** Tablero de permisos de un rol y cambios en lote, contra PostgreSQL real (cada prueba revierte). */
@SpringBootTest
@WithMockUser(authorities = {"VER_ROLES", "VER_PERMISOS", "ASIGNAR_PERMISOS"})
@Transactional
class RolPermisoLoteTest {

    @Autowired RolPermisoService servicio;
    @Autowired JdbcTemplate jdbc;

    private long rol;
    private long otroRol;
    private long verRoles;
    private long verUsuarios;
    private long crearUsuarios;

    @BeforeEach
    void preparar() {
        rol = crearRol();
        otroRol = crearRol();
        verRoles = permiso("VER_ROLES");
        verUsuarios = permiso("VER_USUARIOS");
        crearUsuarios = permiso("CREAR_USUARIOS");
        jdbc.update("INSERT INTO seguridad.roles_permisos (rol_id, permiso_id, creado_por) VALUES (?, ?, 0), (?, ?, 0)",
                rol, verRoles, otroRol, verRoles);
    }

    @Test
    void elTableroTraeTodoElCatalogoConLoAsignadoYElUso() {
        var tablero = servicio.tablero(rol).datos();
        long catalogo = jdbc.queryForObject("SELECT count(*) FROM seguridad.permisos", Long.class);
        assertThat(tablero).hasSize((int) catalogo);
        assertThat(tablero).extracting(PermisoTableroResponse::nombre).isSorted();

        var ver = fila(tablero, verRoles);
        long usoReal = jdbc.queryForObject(
                "SELECT count(*) FROM seguridad.roles_permisos WHERE permiso_id = ?", Long.class, verRoles);
        assertThat(ver.asignado()).isTrue();
        assertThat(ver.roles()).isEqualTo((int) usoReal).isGreaterThanOrEqualTo(2);
        assertThat(fila(tablero, verUsuarios).asignado()).isFalse();
        assertThat(tablero.stream().filter(PermisoTableroResponse::asignado)).hasSize(1);
    }

    @Test
    void elLoteAgregaYQuitaEnUnaSolaTransaccionYEsIdempotente() {
        var resultado = servicio.actualizarLote(rol,
                new RolPermisosLoteRequest(List.of(verUsuarios, crearUsuarios, verUsuarios), List.of(verRoles))).datos();
        assertThat(resultado.agregados()).isEqualTo(2);
        assertThat(resultado.quitados()).isEqualTo(1);
        assertThat(resultado.asignados()).containsExactlyInAnyOrder(verUsuarios, crearUsuarios);

        // Reintento del mismo lote: nada cambia y no falla.
        var reintento = servicio.actualizarLote(rol,
                new RolPermisosLoteRequest(List.of(verUsuarios, crearUsuarios), List.of(verRoles))).datos();
        assertThat(reintento.agregados()).isZero();
        assertThat(reintento.quitados()).isZero();
        assertThat(reintento.asignados()).containsExactlyInAnyOrder(verUsuarios, crearUsuarios);

        // El otro rol no se toca.
        assertThat(servicio.tablero(otroRol).datos().stream().filter(PermisoTableroResponse::asignado)
                .map(PermisoTableroResponse::id)).containsExactly(verRoles);
    }

    @Test
    void unPermisoInexistenteRevierteTodoElLote() {
        assertThatThrownBy(() -> servicio.actualizarLote(rol,
                new RolPermisosLoteRequest(List.of(verUsuarios, Long.MAX_VALUE), List.of(verRoles))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining(String.valueOf(Long.MAX_VALUE));
        assertThat(servicio.tablero(rol).datos().stream().filter(PermisoTableroResponse::asignado)
                .map(PermisoTableroResponse::id)).containsExactly(verRoles);
    }

    @Test
    void validaLaPeticionYProtegeAlAdministrador() {
        assertThatThrownBy(() -> servicio.actualizarLote(rol, new RolPermisosLoteRequest(null, null)))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.actualizarLote(rol,
                new RolPermisosLoteRequest(List.of(verUsuarios), List.of(verUsuarios))))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.actualizarLote(Long.MAX_VALUE,
                new RolPermisosLoteRequest(List.of(verUsuarios), List.of())))
                .isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.tablero(Long.MAX_VALUE)).isInstanceOf(RecursoNoEncontradoException.class);

        long administrador = jdbc.queryForObject(
                "SELECT id FROM seguridad.roles WHERE seguridad.es_rol_protegido(nombre)", Long.class);
        assertThatThrownBy(() -> servicio.actualizarLote(administrador,
                new RolPermisosLoteRequest(List.of(), List.of(verRoles))))
                .isInstanceOf(OperacionNoPermitidaException.class);
        // Agregarle (aunque ya lo tenga) sí se permite.
        assertThat(servicio.actualizarLote(administrador,
                new RolPermisosLoteRequest(List.of(verRoles), List.of())).datos().agregados()).isZero();
    }

    private long crearRol() {
        return jdbc.queryForObject("""
                INSERT INTO seguridad.roles (nombre, descripcion, creado_por, actualizado_por)
                VALUES (?, 'Prueba de tablero', 0, 0) RETURNING id
                """, Long.class, "PRUEBA_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private long permiso(String nombre) {
        return jdbc.queryForObject("SELECT id FROM seguridad.permisos WHERE nombre = ?", Long.class, nombre);
    }

    private static PermisoTableroResponse fila(List<PermisoTableroResponse> tablero, long id) {
        return tablero.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }
}
