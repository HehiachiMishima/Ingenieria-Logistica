package oracle.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Un unico PasswordEncoder (BCrypt) compartido por la autenticacion, el alta de
 * usuarios y el seeding, en lugar de instanciar uno nuevo en cada servicio.
 */
@Configuration
public class CodificacionContrasenas {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
