package oracle.picking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivoPickingTest {

    private static final String CABECERA = "\"FECHA ORDEN\",\"ORDEN\",\"ESTADO DE ASIGNACION\",\"ESTADO CARTON\","
            + "\"UND. ASIG.\",\"UND. EMPAC.\",\"LINEAS ASIG.\",\"AREA\",\"NRO. OLA\",\"CLUSTER\"\n";
    private static final String FILA = "\"26/10/2026\",\"T1\",\"Completed\",\"Empacado\",\"6\",\"6\",\"1\",\"A\",\"O1\",\"Cluster 01\"\n";

    @TempDir
    Path carpeta;

    @Test
    void reemplazarDejaSoloElArchivoNuevoConLaFechaDelServidor() throws Exception {
        ArchivoPicking archivo = new ArchivoPicking(carpeta.toString(), "datos.csv");
        Files.writeString(carpeta.resolve("datos.csv"), CABECERA + FILA);
        Files.writeString(carpeta.resolve("otro.csv"), "sobra");
        String huellaVieja = archivo.actual().orElseThrow().huella();

        Path temporal = archivo.temporal();
        Files.writeString(temporal, CABECERA + FILA + FILA);
        Instant servidor = Instant.parse("2026-11-04T15:30:00Z");
        archivo.reemplazar(temporal, servidor);

        try (Stream<Path> archivos = Files.list(carpeta)) {
            assertEquals(List.of(carpeta.resolve("datos.csv")), archivos.toList());
        }
        ArchivoPicking.Info info = archivo.actual().orElseThrow();
        assertEquals(servidor, info.modificado());
        assertNotEquals(huellaVieja, info.huella());
    }

    @Test
    void sinArchivoNoHayDatos() {
        assertTrue(new ArchivoPicking(carpeta.toString(), "datos.csv").actual().isEmpty());
    }

    @Test
    void validarExigeCabeceraCompletaYFilas() throws Exception {
        Path ok = Files.writeString(carpeta.resolve("ok.csv"), "﻿" + CABECERA + FILA);
        Path vacio = Files.writeString(carpeta.resolve("vacio.csv"), "");
        Path sinFilas = Files.writeString(carpeta.resolve("sinFilas.csv"), CABECERA);
        Path incompleto = Files.writeString(carpeta.resolve("incompleto.csv"), "\"ORDEN\"\n" + FILA);

        assertNull(ArchivoPicking.validar(ok));
        assertEquals("el archivo está vacío", ArchivoPicking.validar(vacio));
        assertEquals("no tiene filas de datos", ArchivoPicking.validar(sinFilas));
        assertTrue(ArchivoPicking.validar(incompleto).startsWith("le faltan columnas: FECHA ORDEN"));
    }

    @Test
    void sinSftpConfiguradoNoSeTocaLaCarpeta() throws Exception {
        ArchivoPicking archivo = new ArchivoPicking(carpeta.toString(), "datos.csv");
        Files.writeString(carpeta.resolve("datos.csv"), CABECERA + FILA);
        SincronizadorPicking sincronizador =
                new SincronizadorPicking(archivo, "", 22, "", "", "", "", 1000);

        sincronizador.sincronizar();

        assertEquals(SincronizadorPicking.Resultado.NO_CONFIGURADO, sincronizador.estado().resultado());
        assertFalse(archivo.actual().isEmpty());
    }
}
