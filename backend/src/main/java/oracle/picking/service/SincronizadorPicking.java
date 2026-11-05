package oracle.picking.service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trae el archivo del WMS por SFTP a la carpeta del panel, al arrancar y en
 * cada ciclo programado. Reglas:
 * <ul>
 *   <li>Si en el servidor no hay archivo, o algo falla, no se toca nada: el
 *       panel sigue con el último archivo bueno.</li>
 *   <li>El archivo se descarga completo y se compara por contenido (SHA-256):
 *       cualquier cambio, por mínimo que sea, lo reemplaza.</li>
 *   <li>Si cambia mientras se descarga (el WMS lo está escribiendo), se
 *       descarta y se reintenta en el siguiente ciclo.</li>
 * </ul>
 */
@Component
public class SincronizadorPicking {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorPicking.class);

    public enum Resultado { PENDIENTE, ACTUALIZADO, SIN_CAMBIOS, SIN_ARCHIVO, ERROR, NO_CONFIGURADO }

    /** Última revisión del servidor (en memoria: los datos son desechables). */
    public record Estado(Resultado resultado, String mensaje, Instant revisado) {}

    private final ArchivoPicking archivo;
    private final String directorio;
    private final DefaultSftpSessionFactory fabrica;
    private final ReentrantLock enCurso = new ReentrantLock();
    /**
     * Hilo de plataforma propio, de baja prioridad: descifrar y calcular la
     * huella de varios MB es trabajo de CPU, y en un hilo virtual ocuparía el
     * hilo portador que comparten las peticiones (con 1 CPU, como en Render,
     * las frenaría cientos de ms). El sistema operativo sí reparte un hilo de
     * plataforma con los demás.
     */
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("picking-sync").daemon().priority(Thread.MIN_PRIORITY).factory());
    private volatile Estado estado;

    public SincronizadorPicking(
            ArchivoPicking archivo,
            @Value("${sftp.host:}") String host,
            @Value("${sftp.port:22}") int puerto,
            @Value("${sftp.username:}") String usuario,
            @Value("${sftp.password:}") String clave,
            @Value("${sftp.remote-directory:}") String directorio,
            @Value("${sftp.known-hosts:}") String knownHosts,
            @Value("${picking.sync.timeout-ms:30000}") int timeoutMs) {
        this.archivo = archivo;
        this.directorio = directorio.endsWith("/") ? directorio : directorio + "/";
        boolean configurado = !host.isBlank() && !usuario.isBlank() && !directorio.isBlank();
        this.fabrica = configurado ? fabrica(host, puerto, usuario, clave, knownHosts, timeoutMs) : null;
        this.estado = configurado
                ? new Estado(Resultado.PENDIENTE, "Primera revisión del servidor en curso.", null)
                : new Estado(Resultado.NO_CONFIGURADO,
                        "El SFTP no está configurado: se usa el archivo que ya está en la carpeta.", null);
    }

    private static DefaultSftpSessionFactory fabrica(
            String host, int puerto, String usuario, String clave, String knownHosts, int timeoutMs) {
        DefaultSftpSessionFactory fabrica = new DefaultSftpSessionFactory(false);
        fabrica.setHost(host);
        fabrica.setPort(puerto);
        fabrica.setUser(usuario);
        fabrica.setPassword(clave);
        fabrica.setTimeout(timeoutMs);
        if (knownHosts.isBlank()) {
            // Sin la huella del servidor no se puede verificar su identidad.
            log.warn("SFTP_KNOWN_HOSTS no está definido: no se verifica la identidad del servidor SFTP.");
            fabrica.setAllowUnknownKeys(true);
        } else {
            fabrica.setKnownHostsResource(new ByteArrayResource(knownHosts.getBytes(StandardCharsets.UTF_8)));
        }
        return fabrica;
    }

    public Estado estado() {
        return estado;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        archivo.borrarTemporal();
        // Sin esperar al primer ciclo, y sin demorar el arranque.
        hilo.execute(this::sincronizar);
    }

    @Scheduled(cron = "${picking.sync.cron:0 5,35 * * * *}", zone = "America/Lima")
    public void programado() {
        hilo.execute(this::sincronizar);
    }

    /** Una sola sincronización a la vez; si ya hay una en curso, esta se omite. */
    public void sincronizar() {
        if (fabrica == null || !enCurso.tryLock()) {
            return;
        }
        try {
            estado = traer();
        } catch (Exception ex) {
            log.warn("Picking: no se pudo traer el archivo del SFTP: {}", ex.getMessage());
            estado = new Estado(Resultado.ERROR,
                    "No se pudo leer el servidor (" + ex.getMessage() + "): se mantienen los datos anteriores.",
                    Instant.now());
        } finally {
            archivo.borrarTemporal();
            enCurso.unlock();
        }
        log.info("Picking: {} - {}", estado.resultado(), estado.mensaje());
    }

    private Estado traer() throws IOException {
        String ruta = directorio + archivo.nombre();
        try (SftpSession sesion = fabrica.getSession()) {
            SftpClient cliente = sesion.getClientInstance();
            SftpClient.Attributes antes = atributos(cliente, ruta);
            if (antes == null) {
                return resultado(Resultado.SIN_ARCHIVO,
                        "No hay archivo en el servidor: se mantienen los datos anteriores.");
            }
            Path temporal = archivo.temporal();
            MessageDigest digest = ArchivoPicking.sha256();
            try (OutputStream salida = new DigestOutputStream(Files.newOutputStream(temporal), digest)) {
                sesion.read(ruta, salida);
            }
            SftpClient.Attributes despues = atributos(cliente, ruta);
            if (despues == null
                    || despues.getSize() != antes.getSize()
                    || !despues.getModifyTime().equals(antes.getModifyTime())
                    || Files.size(temporal) != antes.getSize()) {
                return resultado(Resultado.ERROR,
                        "El archivo cambió durante la descarga: se reintenta en el siguiente ciclo.");
            }
            String invalido = ArchivoPicking.validar(temporal);
            if (invalido != null) {
                return resultado(Resultado.ERROR,
                        "El archivo del servidor no sirve (" + invalido + "): se mantienen los datos anteriores.");
            }
            String huella = HexFormat.of().formatHex(digest.digest());
            Optional<ArchivoPicking.Info> actual = archivo.actual();
            if (actual.isPresent() && actual.get().huella().equals(huella)) {
                return resultado(Resultado.SIN_CAMBIOS, "El archivo del servidor no cambió.");
            }
            archivo.reemplazar(temporal, antes.getModifyTime().toInstant());
            return resultado(Resultado.ACTUALIZADO, "Archivo actualizado desde el servidor.");
        }
    }

    /** Atributos del archivo remoto; null si no existe. */
    private static SftpClient.Attributes atributos(SftpClient cliente, String ruta) throws IOException {
        try {
            SftpClient.Attributes atributos = cliente.stat(ruta);
            return atributos.isRegularFile() ? atributos : null;
        } catch (SftpException ex) {
            if (ex.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw ex;
        }
    }

    private static Estado resultado(Resultado resultado, String mensaje) {
        return new Estado(resultado, mensaje, Instant.now());
    }

    @PreDestroy
    public void cerrar() {
        hilo.shutdownNow();
        if (fabrica != null) {
            fabrica.destroy();
        }
    }
}
