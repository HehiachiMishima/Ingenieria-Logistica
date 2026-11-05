package oracle.picking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import oracle.picking.service.SincronizadorPicking.Resultado;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sincronización contra un servidor SFTP real (Apache SSHD embebido): qué
 * pasa en la carpeta de datos con cada situación del servidor del WMS.
 */
class SincronizadorPickingTest {

    private static final String NOMBRE = "Data_P_TITAN_RDC.csv";
    private static final String CABECERA = "\"FECHA ORDEN\",\"ORDEN\",\"ESTADO DE ASIGNACION\",\"ESTADO CARTON\","
            + "\"UND. ASIG.\",\"UND. EMPAC.\",\"LINEAS ASIG.\",\"AREA\",\"NRO. OLA\",\"CLUSTER\"\n";
    private static final String FILA = "\"26/10/2026\",\"T1\",\"Completed\",\"Empacado\",\"6\",\"6\",\"1\",\"A\",\"O1\",\"Cluster 01\"\n";
    private static final Instant HORA_WMS = Instant.parse("2026-11-04T15:30:00Z");

    @TempDir
    Path remoto;

    @TempDir
    Path carpeta;

    private SshServer servidor;
    private ArchivoPicking archivo;
    private SincronizadorPicking sincronizador;

    @BeforeEach
    void levantarServidor() throws Exception {
        Files.createDirectories(remoto.resolve("output"));
        servidor = SshServer.setUpDefaultServer();
        servidor.setHost("127.0.0.1");
        servidor.setPort(0);
        servidor.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        servidor.setPasswordAuthenticator((usuario, clave, sesion) -> "wms".equals(usuario) && "clave".equals(clave));
        servidor.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        servidor.setFileSystemFactory(new VirtualFileSystemFactory(remoto));
        servidor.start();
        archivo = new ArchivoPicking(carpeta.toString(), NOMBRE);
        sincronizador = new SincronizadorPicking(
                archivo, "127.0.0.1", servidor.getPort(), "wms", "clave", "/output", "", 10_000);
    }

    @AfterEach
    void apagarServidor() throws Exception {
        sincronizador.cerrar();
        servidor.stop(true);
    }

    @Test
    void sinArchivoEnElServidorNoTocaLaCarpeta() throws Exception {
        Path local = Files.writeString(carpeta.resolve(NOMBRE), CABECERA + FILA);

        sincronizador.sincronizar();

        assertEquals(Resultado.SIN_ARCHIVO, sincronizador.estado().resultado());
        assertEquals(CABECERA + FILA, Files.readString(local));
    }

    @Test
    void unArchivoNuevoReemplazaAlAnteriorYBorraLosDemas() throws Exception {
        Files.writeString(carpeta.resolve(NOMBRE), CABECERA + FILA);
        Files.writeString(carpeta.resolve("viejo.csv"), "sobra");
        Files.writeString(carpeta.resolve("notas.txt"), "sobra");
        publicar(CABECERA + FILA + FILA);

        sincronizador.sincronizar();

        assertEquals(Resultado.ACTUALIZADO, sincronizador.estado().resultado());
        assertEquals(List.of(NOMBRE), archivos());
        assertEquals(CABECERA + FILA + FILA, Files.readString(carpeta.resolve(NOMBRE)));
        // Conserva la hora del WMS: el panel muestra la antigüedad real de los datos.
        assertEquals(HORA_WMS, Files.getLastModifiedTime(carpeta.resolve(NOMBRE)).toInstant());
    }

    @Test
    void sinCarpetaNiArchivoPreviosLoCrea() throws Exception {
        Files.delete(carpeta);
        publicar(CABECERA + FILA);

        sincronizador.sincronizar();

        assertEquals(Resultado.ACTUALIZADO, sincronizador.estado().resultado());
        assertEquals(List.of(NOMBRE), archivos());
    }

    @Test
    void elMismoContenidoNoReemplazaNada() throws Exception {
        Path local = Files.writeString(carpeta.resolve(NOMBRE), CABECERA + FILA);
        Instant antes = Files.getLastModifiedTime(local).toInstant();
        publicar(CABECERA + FILA);

        sincronizador.sincronizar();

        assertEquals(Resultado.SIN_CAMBIOS, sincronizador.estado().resultado());
        assertEquals(antes, Files.getLastModifiedTime(local).toInstant());
    }

    @Test
    void unCambioMinimoSiReemplaza() throws Exception {
        Files.writeString(carpeta.resolve(NOMBRE), CABECERA + FILA);
        // Mismo tamaño, un solo carácter distinto.
        publicar(CABECERA + FILA.replace("\"6\",\"6\"", "\"6\",\"5\""));

        sincronizador.sincronizar();

        assertEquals(Resultado.ACTUALIZADO, sincronizador.estado().resultado());
        assertTrue(Files.readString(carpeta.resolve(NOMBRE)).contains("\"6\",\"5\""));
    }

    @Test
    void unArchivoInvalidoNoReemplazaAlBueno() throws Exception {
        Files.writeString(carpeta.resolve(NOMBRE), CABECERA + FILA);
        publicar("\"ORDEN\"\n\"T1\"\n");

        sincronizador.sincronizar();

        assertEquals(Resultado.ERROR, sincronizador.estado().resultado());
        assertEquals(CABECERA + FILA, Files.readString(carpeta.resolve(NOMBRE)));
        assertEquals(List.of(NOMBRE), archivos());
    }

    @Test
    void siElServidorNoRespondeSeMantienenLosDatos() throws Exception {
        Files.writeString(carpeta.resolve(NOMBRE), CABECERA + FILA);
        servidor.stop(true);

        sincronizador.sincronizar();

        assertEquals(Resultado.ERROR, sincronizador.estado().resultado());
        assertEquals(CABECERA + FILA, Files.readString(carpeta.resolve(NOMBRE)));
        assertFalse(Files.exists(carpeta.resolve(".descarga.tmp")));
    }

    private void publicar(String contenido) throws Exception {
        Path ruta = Files.writeString(remoto.resolve("output").resolve(NOMBRE), contenido);
        Files.setLastModifiedTime(ruta, FileTime.from(HORA_WMS));
    }

    private List<String> archivos() throws Exception {
        try (Stream<Path> lista = Files.list(carpeta)) {
            return lista.map((p) -> p.getFileName().toString()).sorted().toList();
        }
    }
}
