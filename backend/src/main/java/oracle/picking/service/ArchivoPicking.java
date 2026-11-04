package oracle.picking.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * La carpeta de datos del panel Picking. Regla: solo existe un archivo, el
 * último bueno. Uno nuevo entra por un temporal oculto y reemplaza al anterior
 * con un movimiento atómico, así quien lo lee ve el viejo o el nuevo, nunca
 * uno a medias.
 */
@Component
public class ArchivoPicking {

    /** Columnas sin las que el panel no puede calcular nada (las mismas que exige el frontend). */
    static final List<String> COLUMNAS = List.of(
            "FECHA ORDEN", "ORDEN", "ESTADO DE ASIGNACION", "ESTADO CARTON", "UND. ASIG.",
            "UND. EMPAC.", "LINEAS ASIG.", "AREA", "NRO. OLA", "CLUSTER");

    private static final String TEMPORAL = ".descarga.tmp";

    /** Archivo en la carpeta y su huella SHA-256 (también es el ETag de la descarga). */
    public record Info(Path ruta, long bytes, Instant modificado, String huella) {}

    private final Path carpeta;
    private final String nombre;
    /** La huella se recalcula solo si cambian el tamaño o la fecha del archivo. */
    private volatile Info cache;

    public ArchivoPicking(
            @Value("${picking.data-dir:data}") String carpeta,
            @Value("${sftp.filename:Data_P_TITAN_RDC.csv}") String nombre) {
        this.carpeta = Path.of(carpeta).toAbsolutePath().normalize();
        this.nombre = nombre;
    }

    public String nombre() {
        return nombre;
    }

    public Path destino() {
        return carpeta.resolve(nombre);
    }

    public synchronized Optional<Info> actual() {
        Path ruta = destino();
        if (!Files.isRegularFile(ruta)) {
            return Optional.empty();
        }
        try {
            long bytes = Files.size(ruta);
            Instant modificado = Files.getLastModifiedTime(ruta).toInstant();
            Info previa = cache;
            if (previa == null || previa.bytes() != bytes || !previa.modificado().equals(modificado)) {
                cache = new Info(ruta, bytes, modificado, huella(ruta));
            }
            return Optional.of(cache);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Temporal oculto dentro de la misma carpeta: el movimiento final es atómico. */
    public Path temporal() throws IOException {
        Files.createDirectories(carpeta);
        return carpeta.resolve(TEMPORAL);
    }

    public void borrarTemporal() {
        try {
            Files.deleteIfExists(carpeta.resolve(TEMPORAL));
        } catch (IOException ignorado) {
            // Se reintenta en la siguiente sincronización.
        }
    }

    /**
     * El temporal pasa a ser el archivo del panel, con la fecha que tenía en el
     * servidor; luego se borra cualquier otro archivo de la carpeta.
     */
    public synchronized void reemplazar(Path temporal, Instant modificado) throws IOException {
        Path ruta = destino();
        try {
            Files.move(temporal, ruta, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporal, ruta, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setLastModifiedTime(ruta, FileTime.from(modificado));
        cache = null;
        try (Stream<Path> archivos = Files.list(carpeta)) {
            for (Path otro : archivos.filter((p) -> !p.equals(ruta) && Files.isRegularFile(p)).toList()) {
                Files.deleteIfExists(otro);
            }
        }
    }

    /** Null si el archivo sirve; si no, el motivo. Exige cabecera con las columnas y al menos una fila. */
    static String validar(Path archivo) throws IOException {
        try (BufferedReader lector = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            String cabecera = lector.readLine();
            if (cabecera == null || cabecera.isBlank()) {
                return "el archivo está vacío";
            }
            List<String> columnas = Arrays.stream(cabecera.replace("﻿", "").split("[,;]"))
                    .map((c) -> c.replace("\"", "").trim())
                    .toList();
            List<String> faltan = COLUMNAS.stream().filter((c) -> !columnas.contains(c)).toList();
            if (!faltan.isEmpty()) {
                return "le faltan columnas: " + String.join(", ", faltan);
            }
            return lector.readLine() == null ? "no tiene filas de datos" : null;
        }
    }

    static String huella(Path archivo) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream entrada = Files.newInputStream(archivo)) {
            byte[] bloque = new byte[64 * 1024];
            for (int leidos; (leidos = entrada.read(bloque)) > 0; ) {
                digest.update(bloque, 0, leidos);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
