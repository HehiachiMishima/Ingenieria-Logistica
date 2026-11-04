package oracle.malla.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import oracle.common.exception.SolicitudInvalidaException;
import oracle.malla.request.CentroMallaRequest;
import oracle.malla.request.CentroMallaRequest.Capacidad;
import oracle.malla.request.CentroMallaRequest.Dotacion;
import oracle.malla.request.CentroMallaRequest.DotacionReal;
import oracle.malla.request.CentroMallaRequest.Flujo;
import oracle.malla.request.CentroMallaRequest.Ola;
import oracle.malla.request.CentroMallaRequest.Turno;
import oracle.malla.request.CentroMallaRequest.Zona;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class MallaExcelTest {

    private static final CentroMallaRequest MALLA = new CentroMallaRequest("9011", "Santa Anita",
            List.of(new Turno("T1", "Turno 1", "07:00", "16:00", "13:00", "Almuerzo", "Traslape", true),
                    new Turno("T3", "Turno 3", "22:00", "06:00", null, null, null, false)),
            List.of(new Flujo("REC", "IL", "06:30", null, "Validar citas", false),
                    new Flujo("OLA", "OPS", "07:00", "13:00", "Picking ola 1", true)),
            List.of(new Ola("1", "Ola 1", "05:00", "07:00", "13:00", "15:00", true, "Regla 07:00")),
            List.of(new Zona("Knapp", 1986, 2200)),
            new Capacidad(120000, 40000, null, 25),
            List.of(new Dotacion("T1", "REC", 12), new Dotacion("T3", "OLA", 30),
                    new Dotacion("T3", "REC", null)),
            new DotacionReal(43, 162, 22, 227, "S44"));

    @Test
    void loQueSeExportaSeVuelveAImportarIgual() throws Exception {
        byte[] excel = MallaExcel.escribir(MALLA);

        CentroMallaRequest leido = MallaExcel.leer(new MockMultipartFile("archivo", "malla.xlsx", null, excel));

        assertEquals(MALLA.codigo(), leido.codigo());
        assertEquals(MALLA.turnos(), leido.turnos());
        assertEquals(MALLA.flujos(), leido.flujos());
        assertEquals(MALLA.olas(), leido.olas());
        assertEquals(MALLA.zonas(), leido.zonas());
        assertEquals(MALLA.capacidad(), leido.capacidad());
        assertEquals(MALLA.dotacionReal(), leido.dotacionReal());
        // La hoja de dotación es una matriz proceso × turno: las celdas vacías vuelven como null.
        assertTrue(leido.dotacion().contains(new Dotacion("T1", "REC", 12)));
        assertTrue(leido.dotacion().contains(new Dotacion("T3", "OLA", 30)));
        assertTrue(leido.dotacion().contains(new Dotacion("T1", "ALM", null)));
        // «Por definir» (personas null) también se exporta, como celda vacía.
        assertTrue(leido.dotacion().contains(new Dotacion("T3", "REC", null)));
    }

    @Test
    void aceptaHorasDeExcelYNombresDeProceso() throws Exception {
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            fila(libro.createSheet("Centro"), 0, "Código", "Nombre");
            fila(libro.getSheet("Centro"), 1, "9040", "Punta Negra");
            Sheet flujos = libro.createSheet("Flujos");
            fila(flujos, 0, "Proceso", "Responsable", "Inicio", "Fin", "Actividad", "Confirmado");
            Row paso = fila(flujos, 1, "Recepción", "Operaciones", null, null, "Descarga", "sí");
            // 07:30 como lo guarda Excel: fracción del día con formato de hora.
            paso.createCell(2).setCellValue(7.5 / 24);
            paso.getCell(2).setCellStyle(libro.createCellStyle());
            paso.getCell(2).getCellStyle().setDataFormat(libro.createDataFormat().getFormat("h:mm"));

            CentroMallaRequest leido = MallaExcel.leer(libro);

            assertEquals(new Flujo("REC", "OPS", "07:30", null, "Descarga", true), leido.flujos().get(0));
        }
    }

    @Test
    void unaHoraInvalidaDiceHojaYFila() throws Exception {
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            fila(libro.createSheet("Centro"), 0, "Código", "Nombre");
            fila(libro.getSheet("Centro"), 1, "9040", "Punta Negra");
            Sheet turnos = libro.createSheet("Turnos");
            fila(turnos, 0, "Código", "Nombre", "Inicio", "Fin");
            fila(turnos, 1, "T1", "Turno 1", "siete", "16:00");

            SolicitudInvalidaException error =
                    assertThrows(SolicitudInvalidaException.class, () -> MallaExcel.leer(libro));

            assertTrue(error.getMessage().contains("Hoja «Turnos», fila 2"), error.getMessage());
        }
    }

    @Test
    void rechazaArchivosQueNoSonXlsx() {
        assertThrows(SolicitudInvalidaException.class, () -> MallaExcel.leer(
                new MockMultipartFile("archivo", "malla.csv", null, "a,b".getBytes())));
    }

    @Test
    void sinLaHojaCentroNoSeLee() throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (XSSFWorkbook libro = new XSSFWorkbook()) {
            libro.createSheet("Turnos");
            libro.write(salida);
        }
        SolicitudInvalidaException error = assertThrows(SolicitudInvalidaException.class,
                () -> MallaExcel.leer(new XSSFWorkbook(new ByteArrayInputStream(salida.toByteArray()))));
        assertTrue(error.getMessage().contains("Centro"), error.getMessage());
    }

    private static Row fila(Sheet hoja, int numero, String... valores) {
        Row fila = hoja.createRow(numero);
        for (int c = 0; c < valores.length; c++) {
            if (valores[c] != null) {
                fila.createCell(c).setCellValue(valores[c]);
            }
        }
        return fila;
    }
}
