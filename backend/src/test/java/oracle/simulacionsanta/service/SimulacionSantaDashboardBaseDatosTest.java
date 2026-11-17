package oracle.simulacionsanta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import oracle.simulacionsanta.repository.SimulacionSantaLotesRepository;
import oracle.simulacionsanta.repository.UbicacionGuardada;
import oracle.simulacionsanta.response.DashboardSanta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard contra PostgreSQL real: los agregados salen del GROUP BY y el endpoint pide su
 * permiso. Cada prueba corre en una transacción que se revierte (también las peticiones de
 * MockMvc, que corren en el mismo hilo).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SimulacionSantaDashboardBaseDatosTest {

  private static final String URL = "/api/simulacion-santa/registros/{id}/dashboard";

  @Autowired private SimulacionSantaDashboardService dashboard;
  @Autowired private SimulacionSantaConfiguracionService configuracion;
  @Autowired private SimulacionSantaLotesRepository lotes;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mvc;

  private long registro;

  @BeforeEach
  void insertarRegistro() {
    List<Long> usuarios =
        jdbc.queryForList("SELECT id FROM seguridad.usuarios ORDER BY id LIMIT 1", Long.class);
    assumeTrue(!usuarios.isEmpty(), "La base no tiene usuarios para creado_por.");
    // C1: completas en el envío 1, 2 y 3; C2: una que llega toda en el envío 3; C3: nada.
    List<ExcelSantaAnita.Fila> filas =
        List.of(
            new ExcelSantaAnita.Fila(1, "R1", "L1", 1, 5, 0, 0),
            new ExcelSantaAnita.Fila(2, "R1", "L2", 1, 2, 3, 0),
            new ExcelSantaAnita.Fila(3, "R2", "L3", 1, 1, 1, 1),
            new ExcelSantaAnita.Fila(4, "R3", "L4", 2, 0, 0, 4));
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO simulacion_santa.registros
                (nombre, nombre_archivo, envio_actual, paletas, rutas,
                 bultos_envio1, bultos_envio2, bultos_envio3,
                 paletas_cluster1, paletas_cluster2, paletas_cluster3,
                 operarios, bultos_hora, orden_cluster1, orden_cluster2, orden_cluster3,
                 configurado_por, creado_por, actualizado_por)
            VALUES ('prueba dashboard', 'prueba.csv', 1, 4, 3, 8, 4, 5, 3, 1, 0, 6, 120,
                    'RAMPAS_PRIMERO', 'PASILLOS_PRIMERO', 'PASILLOS_PRIMERO', 0, ?, 0)
            RETURNING id
            """,
            Long.class,
            usuarios.getFirst());
    assertNotNull(id);
    registro = id;
    lotes.insertarFilas(registro, filas);
    // Rampa 15 en C1 con 2 posiciones; ningún pasillo: C2 no tiene huecos.
    List<UbicacionGuardada> ubicaciones = new ArrayList<>();
    for (int n : PlanoSantaAnita.rampas()) {
      ubicaciones.add(new UbicacionGuardada("RAMPA", n, n == 15 ? 1 : null, n == 15 ? 2 : 0));
    }
    for (int n : PlanoSantaAnita.pasillos()) {
      ubicaciones.add(new UbicacionGuardada("PASILLO", n, null, null));
    }
    lotes.guardarUbicaciones(registro, ubicaciones);
  }

  @Test
  void agregaPorClusterYEnvioDesdeLaBase() {
    DashboardSanta datos = dashboard.dashboard(registro).datos();

    assertEquals(17, datos.bultosTotal());
    assertEquals(4, datos.paletasTotal());
    assertEquals(List.of(8, 4, 5), datos.envios().stream().map(DashboardSanta.Envio::bultos).toList());
    DashboardSanta.Cluster c1 = datos.clusters().get(0);
    assertEquals(List.of(1, 0, 2, 3, 1, 13), List.of(c1.rampas(), c1.pasillos(), c1.posiciones(),
        c1.paletas(), c1.sinUbicar(), c1.bultos()));
    assertEquals(
        List.of(
            new DashboardSanta.EnvioCluster(1, 8, 1),
            new DashboardSanta.EnvioCluster(2, 4, 2),
            new DashboardSanta.EnvioCluster(3, 1, 3)),
        c1.porEnvio());
    DashboardSanta.Cluster c2 = datos.clusters().get(1);
    assertEquals(List.of(0, 1, 1, 4), List.of(c2.posiciones(), c2.paletas(), c2.sinUbicar(), c2.bultos()));
    assertEquals(
        List.of(0, 0, 1),
        c2.porEnvio().stream().map(DashboardSanta.EnvioCluster::paletasCompletas).toList());
    DashboardSanta.Cluster c3 = datos.clusters().get(2);
    assertEquals(List.of(0, 0, 0), List.of(c3.paletas(), c3.sinUbicar(), c3.bultos()));

    // Mismo «sin ubicar» que la simulación guardada.
    var simulacion = configuracion.simulacion(registro, null).datos();
    for (int c = 0; c < 3; c++) {
      assertEquals(simulacion.clusters().get(c).sinUbicar(), datos.clusters().get(c).sinUbicar());
    }
  }

  @Test
  void elEndpointPideSuPermisoYResponde404SiNoExiste() throws Exception {
    mvc.perform(
            get(URL, registro)
                .with(jwt().authorities(new SimpleGrantedAuthority("VER_CONFIGURACION_SIMULACION_SANTA"))))
        .andExpect(status().isForbidden());

    mvc.perform(
            get(URL, registro)
                .with(jwt().authorities(new SimpleGrantedAuthority("VER_DASHBOARD_SIMULACION_SANTA"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.datos.registroId").value(registro))
        .andExpect(jsonPath("$.datos.envioActual").value(1))
        .andExpect(jsonPath("$.datos.clusters.length()").value(3))
        .andExpect(jsonPath("$.datos.clusters[0].sinUbicar").value(1))
        .andExpect(jsonPath("$.datos.clusters[0].porEnvio[1].paletasCompletas").value(2))
        .andExpect(jsonPath("$.datos.envios[2].bultos").value(5));

    mvc.perform(
            get(URL, Long.MAX_VALUE)
                .with(jwt().authorities(new SimpleGrantedAuthority("VER_DASHBOARD_SIMULACION_SANTA"))))
        .andExpect(status().isNotFound());

    // El selector de registros también lo admite con solo el permiso del dashboard.
    mvc.perform(
            get("/api/simulacion-santa/registros/opciones")
                .with(jwt().authorities(new SimpleGrantedAuthority("VER_DASHBOARD_SIMULACION_SANTA"))))
        .andExpect(status().isOk());
  }
}
