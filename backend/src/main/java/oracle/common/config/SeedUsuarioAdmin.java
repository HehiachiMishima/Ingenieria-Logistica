package oracle.common.config;

import java.time.Instant;
import oracle.admin.model.Rol;
import oracle.admin.model.Usuario;
import oracle.admin.model.UsuarioRol;
import oracle.admin.repository.RolRepository;
import oracle.admin.repository.UsuarioRepository;
import oracle.admin.repository.UsuarioRolRepository;
import oracle.common.support.PoliticaContrasena;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Crea el usuario administrador inicial a partir de las credenciales del .env.
 * Es idempotente: si el usuario ya existe no hace nada. La contrasena se
 * guarda cifrada con BCrypt. No se registra el valor de la contrasena en logs.
 */
@Component
public class SeedUsuarioAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUsuarioAdmin.class);

    private static final Long SISTEMA = 0L;

    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final PasswordEncoder passwordEncoder;

    private final String usuario;
    private final String contrasena;
    private final String nombre;
    private final String apellido;
    private final String email;
    private final String telefono;
    private final String rolNombre;
    private final boolean activo;

    public SeedUsuarioAdmin(
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            UsuarioRolRepository usuarioRolRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.usuario:}") String usuario,
            @Value("${app.seed.contrasena:}") String contrasena,
            @Value("${app.seed.nombre:Usuario}") String nombre,
            @Value("${app.seed.apellido:Sistema}") String apellido,
            @Value("${app.seed.email:}") String email,
            @Value("${app.seed.telefono:}") String telefono,
            @Value("${app.seed.rol:}") String rolNombre,
            @Value("${app.seed.activo:true}") boolean activo) {
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.usuarioRolRepository = usuarioRolRepository;
        this.passwordEncoder = passwordEncoder;
        this.usuario = usuario;
        this.contrasena = contrasena;
        this.nombre = nombre;
        this.apellido = apellido;
        this.email = email;
        this.telefono = telefono;
        this.rolNombre = rolNombre;
        this.activo = activo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(usuario) || !StringUtils.hasText(contrasena) || !StringUtils.hasText(email)) {
            log.warn("Seeding omitido: USER_ADMIN, USER_PASSWORD y USER_EMAIL son obligatorios en .env.");
            return;
        }

        if (usuarioRepository.existsByUsuario(usuario)|| usuarioRepository.existsByEmail(email)) {
            log.info("Usuario semilla '{}' ya existe; no se crea de nuevo.", usuario);
            return;
        }

        if (!PoliticaContrasena.cumple(contrasena)) {
            throw new IllegalStateException("USER_PASSWORD no cumple la politica: " + PoliticaContrasena.MENSAJE);
        }

        Instant ahora = Instant.now();

        Usuario nuevo = new Usuario();
        nuevo.setUsuario(usuario);
        nuevo.setContrasena(passwordEncoder.encode(contrasena));
        nuevo.setNombre(nombre);
        nuevo.setApellido(apellido);
        nuevo.setEmail(email);
        if (StringUtils.hasText(telefono)) {
            nuevo.setTelefono(telefono);
        }
        nuevo.setActivo(activo);
        nuevo.setCreadoEn(ahora);
        nuevo.setActualizadoEn(ahora);
        nuevo.setCreadoPor(SISTEMA);
        nuevo.setActualizadoPor(SISTEMA);
        Usuario guardado = usuarioRepository.save(nuevo);

        if (StringUtils.hasText(rolNombre)) {
            Rol rol = rolRepository.findByNombre(rolNombre).orElse(null);
            if (rol == null) {
                log.warn("Rol '{}' no existe; el usuario semilla '{}' se crea sin rol.", rolNombre, usuario);
            } else {
                UsuarioRol asignacion = new UsuarioRol();
                asignacion.setUsuario(guardado);
                asignacion.setRol(rol);
                asignacion.setCreadoPor(SISTEMA);
                asignacion.setCreadoEn(ahora);
                usuarioRolRepository.save(asignacion);
                log.info("Usuario semilla '{}' creado con el rol '{}'.", usuario, rolNombre);
            }
        } else {
            log.info("Usuario semilla '{}' creado sin rol.", usuario);
        }
    }
}
