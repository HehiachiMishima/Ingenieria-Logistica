package oracle.admin.observabilidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import oracle.common.config.Transacciones;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest
@WithMockUser(authorities = {"VER_ACTIVIDAD", "VER_AUDITORIA"})
@Transactional
class ConsultasObservabilidadTest {
    @Autowired ConsultasObservabilidad consultas;
    @Autowired ResumenObservabilidad resumen;
    @Autowired ColaPeticiones cola;
    @Autowired JdbcTemplate jdbc;
    @Autowired Transacciones transacciones;

    private static ColaPeticiones.Traza traza(String usuario, String ruta, int estado, long ms) {
        return new ColaPeticiones.Traza(UUID.randomUUID(), 1L, usuario, "GET", ruta, estado, ms, "prueba",
                java.time.Instant.now());
    }

    @Test
    void elLoteSumaAlResumenPorHoraUnaSolaVezAunqueSeReintente() {
        String usuario = UUID.randomUUID().toString();
        String ruta = "/api/picking/" + usuario;
        var lote = List.of(traza(usuario, ruta, 200, 40), traza(usuario, ruta, 404, 120), traza(usuario, ruta, 503, 900));
        cola.guardar(lote);
        cola.guardar(lote); // reintento del mismo lote: las trazas ya existen y no vuelven a sumar
        var fila = jdbc.queryForMap("""
                SELECT sum(peticiones) AS peticiones, sum(errores_cliente) AS cliente, sum(errores_servidor) AS servidor,
                       sum(hasta_50) AS h50, sum(hasta_200) AS h200, sum(hasta_1000) AS h1000
                FROM bitacora.peticiones_hora WHERE ruta = ?
                """, ruta);
        assertThat(fila).containsEntry("peticiones", java.math.BigDecimal.valueOf(3))
                .containsEntry("cliente", java.math.BigDecimal.ONE).containsEntry("servidor", java.math.BigDecimal.ONE)
                .containsEntry("h50", java.math.BigDecimal.ONE).containsEntry("h200", java.math.BigDecimal.valueOf(2))
                .containsEntry("h1000", java.math.BigDecimal.valueOf(3));

        var endpoint = resumen.endpoints(1).stream().filter(e -> e.ruta().equals(ruta)).findFirst().orElseThrow();
        assertThat(endpoint.peticiones()).isEqualTo(3);
        assertThat(endpoint.errores()).isEqualTo(2);
        assertThat(endpoint.porcentaje()).isEqualTo(66.7);
        assertThat(endpoint.p95()).isEqualTo(1000);
        assertThat(endpoint.maxima()).isEqualTo(900);

        var actividad = resumen.actividad(1, usuario);
        assertThat(actividad.usuarios()).isEqualTo(1);
        assertThat(actividad.acciones()).isEqualTo(3);
        assertThat(actividad.paneles()).containsExactly(new ResumenObservabilidad.PanelActividad("PANEL_PICKING", 1, 3));
        assertThat(actividad.calor().stream().mapToLong(ResumenObservabilidad.CeldaActividad::acciones).sum()).isEqualTo(3);
        assertThat(actividad.serie()).hasSize(24);
        assertThat(actividad.serie().getLast()).isEqualTo(
                new ResumenObservabilidad.PuntoActividad(actividad.serie().getLast().periodo(), 1, 3));
        assertThat(actividad.semanas()).hasSize(12);
        assertThat(actividad.semanas().getLast().usuarios()).isEqualTo(1);

        var monitoreo = resumen.monitoreo(1);
        assertThat(monitoreo.peticiones()).isGreaterThanOrEqualTo(3);
        assertThat(monitoreo.serie()).hasSize(24);
        assertThat(monitoreo.serie().getLast().peticiones()).isGreaterThanOrEqualTo(3);
        assertThat(monitoreo.calor()).isNotEmpty()
                .allMatch(c -> c.dia() >= 1 && c.dia() <= 7 && c.desde() % 4 == 0 && c.desde() <= 20);
    }

    @Test
    void elPercentilInterpolaDentroDelIntervalo() {
        long[] hasta = {10, 50, 80, 90, 100, 100, 100, 100, 100};
        assertThat(Histograma.percentil(100, hasta, 0.95)).isEqualTo(400);
        assertThat(Histograma.percentil(0, new long[9], 0.95)).isZero();
        assertThat(Histograma.percentil(5, new long[9], 0.95)).isEqualTo(5000);
    }

    @Test
    void elResumenDeAuditoriaComparaConElPeriodoAnteriorYRellenaLosDias() {
        String usuario = UUID.randomUUID().toString();
        for (String resultado : List.of("Éxito", "Éxito", "Denegado")) {
            new EventoAdministrativo(jdbc, UUID.randomUUID(), 1L, usuario, "Actualizar", "Usuario", "7", "prueba")
                    .insertar(resultado, "7");
        }
        jdbc.update("""
                INSERT INTO bitacora.auditoria_eventos(solicitud,actor_id,usuario,accion,entidad,registro,resultado,correlacion,ocurrido_en)
                VALUES (gen_random_uuid(),1,?,'Crear','Usuario','8','Éxito','prueba',now() - interval '10 days')
                """, usuario);
        var r = resumen.auditoria(7, usuario, null);
        assertThat(r.cambios()).isEqualTo(2);
        assertThat(r.cambiosPrevios()).isEqualTo(1);
        assertThat(r.denegados()).isEqualTo(1);
        assertThat(r.personas()).isEqualTo(1);
        assertThat(r.serie()).hasSize(7);
        assertThat(r.serie().stream().mapToLong(ResumenObservabilidad.PuntoAuditoria::exito).sum()).isEqualTo(2);
        assertThat(r.entidades()).containsExactly(new ResumenObservabilidad.Conteo("Usuario", 2));
        assertThat(r.denegadosPorUsuario()).containsExactly(new ResumenObservabilidad.Conteo(usuario, 1));
        assertThat(r.calor().stream().mapToLong(ResumenObservabilidad.CeldaAuditoria::total).sum()).isEqualTo(3);
        assertThat(r.matriz()).containsExactly(new ResumenObservabilidad.CeldaMatriz(usuario, "Usuario", 2));
        assertThatThrownBy(() -> resumen.monitoreo(90)).isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void laPurgaQuitaLaTrazaPeroConservaElCambioYElUsuario() {
        var solicitud = UUID.randomUUID();
        String usuario = solicitud.toString();
        var evento = new EventoAdministrativo(jdbc, solicitud, 1L, usuario, "Guardar predeterminado",
                "Archivo Titan", "345", usuario);
        evento.insertar("Éxito", "345");
        jdbc.update("""
                INSERT INTO bitacora.peticiones(solicitud,actor_id,usuario,metodo,ruta,estado,duracion_ms,correlacion,ocurrido_en)
                VALUES(?,1,?,'POST','/prueba',200,10,?,now()-interval '100 years')
                """, solicitud, usuario, usuario);
        var antes = consultas.auditoria(7, usuario, "345", null, 1, 10).datos().contenido();
        assertThat(antes).hasSize(1);
        assertThat(antes.getFirst().get("estado")).isEqualTo(200);
        consultas.purgarPeticiones();
        var despues = consultas.auditoria(7, usuario, "345", null, 1, 10).datos().contenido();
        assertThat(despues).hasSize(1);
        assertThat(despues.getFirst()).containsEntry("usuario", usuario)
                .containsEntry("resultado", "Éxito").containsEntry("estado", null);
    }

    @Test
    void laMasLentaDeCadaHoraSoloSubeYLlegaAlResumenDeLaApi() {
        String usuario = UUID.randomUUID().toString();
        String ruta = "/api/picking/" + usuario;
        cola.guardar(List.of(traza(usuario, ruta, 200, 40), traza(usuario, ruta, 200, 900)));
        cola.guardar(List.of(traza(usuario, ruta, 200, 1500)));
        cola.guardar(List.of(traza(usuario, ruta, 200, 15))); // una más rápida no baja el máximo

        Long hora = jdbc.queryForObject("SELECT max(duracion_max_ms) FROM bitacora.peticiones_hora WHERE ruta = ?",
                Long.class, ruta);
        assertThat(hora).isEqualTo(1500);
        var endpoint = resumen.endpoints(1).stream().filter(e -> e.ruta().equals(ruta)).findFirst().orElseThrow();
        assertThat(endpoint.maxima()).isEqualTo(1500);
        assertThat(endpoint.peticiones()).isEqualTo(4);
    }

    @Test
    @WithMockUser(authorities = "VER_MONITOREO")
    void lasPeticionesDeUnaApiTraenElUsuarioYFiltranLasLentasYLosErrores() {
        String usuario = UUID.randomUUID().toString();
        String ruta = "/api/picking/" + usuario;
        cola.guardar(List.of(traza(usuario, ruta, 200, 10), traza(usuario, ruta, 200, 300),
                traza(usuario, ruta, 500, 900), traza(usuario, ruta, 404, 5)));

        var todas = consultas.peticiones(1, "GET", ruta, false, null, 1, 10).datos();
        assertThat(todas.total()).isEqualTo(4);
        assertThat(todas.contenido()).allMatch(f -> usuario.equals(f.get("usuario")));

        var lentas = consultas.peticiones(1, "GET", ruta, false, 299L, 1, 10).datos();
        assertThat(lentas.total()).isEqualTo(2);
        assertThat(lentas.contenido()).allMatch(f -> ((Number) f.get("duracion_ms")).longValue() > 299);

        var erroresLentos = consultas.peticiones(1, "GET", ruta, true, 299L, 1, 10).datos();
        assertThat(erroresLentos.total()).isEqualTo(1);
        assertThat(erroresLentos.contenido().getFirst()).containsEntry("estado", 500);

        assertThatThrownBy(() -> consultas.peticiones(1, "GET", ruta, false, 0L, 1, 10))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> consultas.peticiones(1, "GET", ruta, false, 3_600_001L, 1, 10))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void laActividadExcluyeLaSesionTecnicaYPaginaConTotal() {
        String usuario = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO bitacora.peticiones(solicitud,actor_id,usuario,metodo,ruta,estado,duracion_ms,correlacion)
                SELECT gen_random_uuid(),1,?,'GET','/prueba',200,10,'prueba' FROM generate_series(1,51)
                """, usuario);
        for (String ruta : List.of("/api/auth/refresh", "/api/auth/sesion", "/api/health")) {
            jdbc.update("""
                    INSERT INTO bitacora.peticiones(solicitud,actor_id,usuario,metodo,ruta,estado,duracion_ms,correlacion)
                    VALUES(gen_random_uuid(),1,?,'GET',?,200,1,'prueba')
                    """, usuario, ruta);
        }
        var primera = consultas.actividad(1, usuario, false, 1, 10).datos();
        var ultima = consultas.actividad(1, usuario, false, 6, 10).datos();
        assertThat(primera.contenido()).hasSize(10).allMatch(f -> "/prueba".equals(f.get("ruta")));
        assertThat(primera.total()).isEqualTo(51);
        assertThat(primera.totalPaginas()).isEqualTo(6);
        assertThat(ultima.contenido()).hasSize(1);
        assertThat((Long) ultima.contenido().getFirst().get("id"))
                .isLessThan((Long) primera.contenido().getLast().get("id"));
        assertThatThrownBy(() -> consultas.actividad(91, usuario, false, 1, 10))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> consultas.actividad(1, usuario, false, 1, 51))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> consultas.actividad(1, usuario, false, 1001, 10))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void laActividadFiltraElUsuarioSinDistinguirMayusculasYSoloLosCambios() {
        String login = "Prueba-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seguridad.usuarios(usuario,contrasena,nombre,apellido,email,actualizado_por)
                VALUES (?,'x','Prueba','Actividad',?,1)
                """, login, login + "@prueba.test");
        jdbc.update("""
                INSERT INTO bitacora.peticiones(solicitud,actor_id,usuario,metodo,ruta,estado,duracion_ms,correlacion)
                SELECT gen_random_uuid(),1,?,'GET','/prueba',200,10,'prueba' FROM generate_series(1,2)
                """, login);
        var cambio = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bitacora.peticiones(solicitud,actor_id,usuario,metodo,ruta,estado,duracion_ms,correlacion)
                VALUES (?,1,?,'DELETE','/api/simulacion/{id}',409,10,'prueba')
                """, cambio, login);
        new EventoAdministrativo(jdbc, cambio, 1L, login, "Eliminar", "Archivo Titan", "345", "prueba")
                .insertar("Fallo", "345");

        String escrito = login.toLowerCase();
        assertThat(consultas.actividad(1, escrito, false, 1, 10).datos().total()).isEqualTo(3);
        var cambios = consultas.actividad(1, escrito, true, 1, 10).datos();
        assertThat(cambios.total()).isEqualTo(1);
        assertThat(cambios.contenido().getFirst()).containsEntry("accion", "Eliminar")
                .containsEntry("resultado", "Fallo").containsEntry("estado", 409);
        assertThat(consultas.auditoria(7, login.toUpperCase(), null, null, 1, 10).datos().total()).isEqualTo(1);
    }

    /** Resultado de un alta, como los record de respuesta de cada módulo. */
    public record Creado(Long id) {}

    @Test
    void unaPeticionConVariasTransaccionesRegistraUnSoloCambioConElIdCreado() {
        var solicitud = UUID.randomUUID();
        var peticion = new MockHttpServletRequest();
        peticion.setAttribute(EventoAdministrativo.CLAVE, new EventoAdministrativo(jdbc, solicitud, 1L,
                solicitud.toString(), "Crear", "Responsable de área", "5", "prueba"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(peticion));
        try {
            // Anidada y luego otra: antes, el segundo INSERT chocaba con la solicitud UNIQUE y tumbaba el cambio.
            transacciones.ejecutar(() -> transacciones.ejecutar(() -> ApiResponse.ok("Creado.", new Creado(12L))));
            transacciones.ejecutar(() -> new Creado(13L));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        assertThat(jdbc.queryForList(
                "SELECT registro, resultado FROM bitacora.auditoria_eventos WHERE solicitud = ?", solicitud))
                .containsExactly(Map.of("registro", "5/12", "resultado", "Éxito"));
    }
}
