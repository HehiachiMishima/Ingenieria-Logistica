package oracle.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Cachea (Caffeine, TTL corto) las autoridades de un usuario para no ejecutar
 * las 2 consultas SQL (existencia/activo + vista v_permisos_finales) en CADA
 * peticion autenticada.
 *
 * Trade-off de seguridad: un cambio de rol/permiso o una desactivacion se
 * refleja al instante porque los servicios que los modifican llaman a
 * invalidar()/invalidarTodo() tras el commit; el TTL es solo la red de
 * seguridad. Se puede desactivar con app.security.permission-cache.enabled=false
 * (sin cache, se resuelve por peticion como antes).
 *
 * La clave incluye una generacion que invalidarTodo() avanza, para que una
 * carga en vuelo durante la invalidacion no deje autoridades viejas cacheadas.
 */
@Component
public class AutorizacionCache {

    /** Autorizacion resuelta de un usuario. */
    public record Autorizacion(boolean activo, String usuario, List<SimpleGrantedAuthority> autoridades,
            Instant credencialesDesde) {
    }

    private record Clave(long generacion, Long usuarioId) {
    }

    private final FuenteAutorizacion fuente;
    private final boolean habilitado;
    private final Cache<Clave, Autorizacion> cache;
    private final AtomicLong generacion = new AtomicLong();
    private final List<Runnable> oyentes = new CopyOnWriteArrayList<>();

    public AutorizacionCache(
            FuenteAutorizacion fuente,
            @Value("${app.security.permission-cache.enabled:true}") boolean habilitado,
            @Value("${app.security.permission-cache.ttl-ms:30000}") long ttlMs,
            @Value("${app.security.permission-cache.max-size:10000}") long maxSize) {
        this.fuente = fuente;
        this.habilitado = habilitado;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMillis(ttlMs))
                .build();
    }

    /** Resuelve la autorizacion del usuario (cacheada si esta habilitada). */
    public Autorizacion cargar(Long usuarioId) {
        if (!habilitado) {
            return consultar(usuarioId);
        }
        return cache.get(new Clave(generacion.get(), usuarioId), clave -> consultar(clave.usuarioId()));
    }

    /** Invalida la entrada de un usuario (tras cambiar sus roles/permisos/estado). */
    public void invalidar(Long usuarioId) {
        if (usuarioId != null) {
            cache.invalidate(new Clave(generacion.get(), usuarioId));
        }
        oyentes.forEach(Runnable::run);
    }

    /** Invalida todas las entradas (cambios de rol o de permisos globales). */
    public void invalidarTodo() {
        generacion.incrementAndGet();
        cache.invalidateAll();
        oyentes.forEach(Runnable::run);
    }

    /**
     * Registra una accion que se ejecuta en cada invalidacion: las caches que
     * derivan de roles y permisos (p. ej. el resumen de usuarios) se
     * descartan junto con esta.
     */
    public void alInvalidar(Runnable accion) {
        oyentes.add(accion);
    }

    private Autorizacion consultar(Long usuarioId) {
        FuenteAutorizacion.Identidad identidad = fuente.buscar(usuarioId)
                .orElseThrow(() -> new BadCredentialsException("Usuario inexistente."));
        List<SimpleGrantedAuthority> autoridades = identidad.permisos().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new Autorizacion(identidad.activo(), identidad.usuario(), autoridades, identidad.credencialesDesde());
    }
}
