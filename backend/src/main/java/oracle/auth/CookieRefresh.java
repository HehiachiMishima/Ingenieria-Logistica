package oracle.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Cookie del refresh token: HttpOnly (el JavaScript no la lee, un XSS no se
 * la lleva), SameSite=Strict (otro sitio no la envía) y limitada a /api/auth
 * (no viaja con el resto de la API).
 */
@Component
public class CookieRefresh {

    static final String RUTA = "/api/auth";

    private final String nombre;
    private final boolean segura;

    public CookieRefresh(
            @Value("${app.auth.refresh.cookie}") String nombre,
            @Value("${app.auth.refresh.cookie-secure}") boolean segura) {
        this.nombre = nombre;
        this.segura = segura;
    }

    public String nombre() {
        return nombre;
    }

    public String crear(SesionRefreshService.Emitido emitido) {
        long segundos = Math.max(0, Duration.between(Instant.now(), emitido.expiraEn()).toSeconds());
        return base(emitido.token()).maxAge(segundos).build().toString();
    }

    public String borrar() {
        return base("").maxAge(0).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String valor) {
        return ResponseCookie.from(nombre, valor).httpOnly(true).secure(segura).sameSite("Strict").path(RUTA);
    }
}
