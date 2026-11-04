package oracle.common.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import oracle.common.exception.LimiteExcedidoException;
import org.springframework.stereotype.Component;

/**
 * Limita los intentos de login (ventanas deslizantes en memoria, un nodo).
 *
 * Tres cubos por intento: IP + usuario (10/min), IP (30/min) y CUENTA, sin
 * importar la IP (20 cada 15 min), que frena el ataque distribuido desde
 * muchas IPs contra una cuenta conocida como @admin. Cada intento se RESERVA
 * antes de verificar la contrasena (comprobar y contar en un solo paso): una
 * rafaga en paralelo no puede colar mas intentos que el tope. Un login
 * correcto libera los cubos de usuario y de cuenta; el de IP no, para que
 * iniciar sesion con una cuenta propia no regale intentos contra otras.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_INTENTOS = 10;
    private static final int MAX_INTENTOS_POR_IP = 30;
    private static final long VENTANA_MS = 60_000L;
    private static final int MAX_INTENTOS_POR_CUENTA = 20;
    private static final long VENTANA_CUENTA_MS = 15 * 60_000L;
    private static final int MAX_CLAVES = 10_000;

    private final ConcurrentHashMap<String, Deque<Long>> intentos = new ConcurrentHashMap<>();

    /** Claves de los cubos consumidos por un intento de login. */
    public record Reserva(String usuario, String ip, String cuenta) {
    }

    /** Cuenta el intento en los tres cubos; 429 si alguno ya esta lleno. */
    public Reserva reservarLogin(String ip, String usuario) {
        Reserva reserva = new Reserva(claveUsuario(ip, usuario), claveIp(ip), claveCuenta(usuario));
        consumir(reserva.usuario(), MAX_INTENTOS, VENTANA_MS);
        consumir(reserva.ip(), MAX_INTENTOS_POR_IP, VENTANA_MS);
        consumir(reserva.cuenta(), MAX_INTENTOS_POR_CUENTA, VENTANA_CUENTA_MS);
        acotarTamano();
        return reserva;
    }

    /** Login correcto: libera los cubos de usuario y de cuenta (no el de IP). */
    public void loginCorrecto(Reserva reserva) {
        limpiar(reserva.usuario());
        limpiar(reserva.cuenta());
    }

    private void consumir(String clave, int maximo, long ventanaMs) {
        Deque<Long> cola = intentos.computeIfAbsent(clave, k -> new ArrayDeque<>());
        synchronized (cola) {
            podar(cola, ventanaMs);
            if (cola.size() >= maximo) {
                throw new LimiteExcedidoException("Demasiados intentos de acceso. Intente de nuevo mas tarde.");
            }
            cola.addLast(System.currentTimeMillis());
        }
    }

    /** Cambio de la propia contrasena: 5 intentos cada 15 min por usuario. Devuelve la clave a liberar. */
    public String reservarCambioContrasena(Long usuarioId) {
        String clave = "cambio:" + usuarioId;
        consumir(clave, 5, VENTANA_CUENTA_MS);
        return clave;
    }

    /** Clave de cubo por cuenta (cualquier IP). */
    public static String claveCuenta(String usuario) {
        return "cuenta:" + usuario.trim().toLowerCase();
    }

    /** Limite por defecto (por IP+usuario). */
    public void verificar(String clave) {
        verificar(clave, MAX_INTENTOS);
    }

    /** Lanza 429 si la clave ya alcanzo {@code maxIntentos} en la ventana actual. */
    public void verificar(String clave, int maxIntentos) {
        Deque<Long> cola = intentos.get(clave);
        if (cola == null) {
            return;
        }
        synchronized (cola) {
            podar(cola, VENTANA_MS);
            if (cola.size() >= maxIntentos) {
                throw new LimiteExcedidoException("Demasiados intentos de acceso. Intente de nuevo en un minuto.");
            }
        }
    }

    /** Limite por IP (mas holgado que el de IP+usuario para no bloquear NAT compartidos). */
    public void verificarPorIp(String ip) {
        verificar(claveIp(ip), MAX_INTENTOS_POR_IP);
    }

    /** Clave de cubo por usuario (IP + usuario, para no bloquear a todos desde una IP). */
    public static String claveUsuario(String ip, String usuario) {
        return ip + "|" + usuario.trim().toLowerCase();
    }

    /** Clave de cubo por IP. */
    public static String claveIp(String ip) {
        return "ip:" + ip;
    }

    /** Registra un intento fallido. */
    public void registrarFallo(String clave) {
        Deque<Long> cola = intentos.computeIfAbsent(clave, k -> new ArrayDeque<>());
        synchronized (cola) {
            podar(cola, VENTANA_MS);
            cola.addLast(System.currentTimeMillis());
        }
        acotarTamano();
    }

    /**
     * Cota el numero de claves sin borrar TODOS los contadores (un clear() global
     * regalaria intentos nuevos al atacante). Primero descarta las colas ya
     * vencidas; si aun sobran, elimina las sobrantes de forma acotada.
     */
    private void acotarTamano() {
        if (intentos.size() <= MAX_CLAVES) {
            return;
        }
        long limite = System.currentTimeMillis() - VENTANA_CUENTA_MS;
        var it = intentos.entrySet().iterator();
        while (it.hasNext() && intentos.size() > MAX_CLAVES) {
            Deque<Long> cola = it.next().getValue();
            synchronized (cola) {
                while (!cola.isEmpty() && cola.peekFirst() < limite) {
                    cola.pollFirst();
                }
                if (cola.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    /** Limpia los intentos tras un login exitoso. */
    public void limpiar(String clave) {
        intentos.remove(clave);
    }

    private void podar(Deque<Long> cola, long ventanaMs) {
        long limite = System.currentTimeMillis() - ventanaMs;
        while (!cola.isEmpty() && cola.peekFirst() < limite) {
            cola.pollFirst();
        }
    }
}
