package oracle.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Seguridad stateless basada en JWT (HS256). La autorizacion fina vive en
 * los @PreAuthorize de cada endpoint; aqui solo se exige autenticacion.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain cadenaDeSeguridad(
            HttpSecurity http,
            CorsConfigurationSource fuenteCors,
            PermisosJwtConverter convertidor,
            RestAuthenticationEntryPoint puntoEntrada,
            RestAccessDeniedHandler manejadorDenegado) throws Exception {

        http
                .cors(cors -> cors.configurationSource(fuenteCors))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/health")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(excepciones -> excepciones
                        .authenticationEntryPoint(puntoEntrada)
                        .accessDeniedHandler(manejadorDenegado))
                .oauth2ResourceServer(recurso -> recurso
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(convertidor))
                        .authenticationEntryPoint(puntoEntrada));

        return http.build();
    }

    /**
     * CORS para el frontend Angular. Los origenes permitidos se configuran en
     * app.cors.allowed-origins (lista separada por comas). Con credenciales
     * activadas NO se permite el comodin "*": hay que listar los origenes.
     */
    @Bean
    CorsConfigurationSource fuenteCors(
            @Value("${app.cors.allowed-origins:http://localhost:4200}") List<String> origenes,
            @Value("${app.cors.max-age:3600}") Long maxAge) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origenes);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(maxAge);

        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/**", config);
        log.info("CORS habilitado para origenes: {} (maxAge={}s)", origenes, maxAge);
        return fuente;
    }

    @Bean
    JwtEncoder codificadorJwt(@Value("${app.jwt.secret}") String secreto) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(clave(secreto)));
    }

    @Bean
    JwtDecoder decodificadorJwt(
            @Value("${app.jwt.secret}") String secreto,
            @Value("${app.jwt.issuer}") String emisor) {
        NimbusJwtDecoder decodificador = NimbusJwtDecoder.withSecretKey(clave(secreto))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decodificador.setJwtValidator(JwtValidators.createDefaultWithIssuer(emisor));
        return decodificador;
    }

    private SecretKey clave(String secreto) {
        byte[] bytes = secreto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET (app.jwt.secret) debe tener al menos 32 bytes para HS256.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
