package com.cloudcentinel.parkingapp.auth;

import com.cloudcentinel.parkingapp.shared.cognito.CognitoAdminClient;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.shared.exception.UnauthorizedException;
import com.cloudcentinel.parkingapp.shared.rut.RutUtils;
import com.cloudcentinel.parkingapp.session.SessionRepository;
import com.cloudcentinel.parkingapp.terminal.Terminal;
import com.cloudcentinel.parkingapp.terminal.TerminalRepository;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.UUID;

/**
 * Lógica de negocio de autenticación para terminales POS y gestión de sesiones.
 *
 * <h2>Responsabilidades</h2>
 * <ol>
 *   <li>Login mediado para OPERATOR desde POS Android ({@link #deviceLogin}).</li>
 *   <li>Logout del propio caller ({@link #logout}).</li>
 *   <li>Cierre remoto de sesión por ADMIN ({@link #adminCloseSession}).</li>
 * </ol>
 *
 * <h2>Por qué el POS no habla directamente con Cognito</h2>
 * <p>El flujo {@code ADMIN_USER_PASSWORD_AUTH} requiere credenciales IAM del
 * servidor y no puede ejecutarse desde un cliente. Esto obliga a que el POS
 * pase por el backend, lo que a su vez permite al backend:</p>
 * <ul>
 *   <li>Validar que el terminal está registrado y en estado {@code ONLINE}
 *       antes de autenticar.</li>
 *   <li>Registrar la sesión en PostgreSQL para el enforcement de sesión única.</li>
 *   <li>Verificar que el usuario es OPERATOR y está ACTIVE.</li>
 * </ul>
 *
 * <h2>Orden de operaciones en deviceLogin</h2>
 * <p>La validación del terminal ocurre ANTES de llamar a Cognito. Si el terminal
 * no existe o está fuera de línea, no se consume el rate limit de autenticación
 * de Cognito ni se revelan al cliente mensajes sobre las credenciales del usuario.</p>
 */
@Service
public class AuthService {

    private final CognitoAdminClient cognito;
    private final SessionRepository  sessions;
    private final UserRepository     users;
    private final TerminalRepository terminals;

    public AuthService(CognitoAdminClient cognito,
                       SessionRepository sessions,
                       UserRepository users,
                       TerminalRepository terminals) {
        this.cognito   = cognito;
        this.sessions  = sessions;
        this.users     = users;
        this.terminals = terminals;
    }

    /**
     * Autentica un OPERATOR desde un terminal POS y registra la sesión activa.
     *
     * <h2>Orden de validaciones</h2>
     * <p>Todas las validaciones de negocio ocurren <b>antes</b> de llamar a
     * Cognito. Esto evita consumir el rate limit de autenticación para
     * terminales inválidos, roles incorrectos o sesiones duplicadas.</p>
     * <ol>
     *   <li>Normalizar y validar RUT (formato + módulo 11).</li>
     *   <li>Buscar usuario por RUT en PostgreSQL → verificar estado {@code ACTIVE}.</li>
     *   <li>Verificar que el rol sea {@code OPERATOR}. ADMIN y CUSTOMER solo
     *       se autentican por el dashboard web (Cognito directo) y son rechazados
     *       aquí.</li>
     *   <li>Verificar que {@code serialNumber} esté presente (obligatorio para
     *       OPERATOR).</li>
     *   <li>Validar que el terminal exista y esté en estado {@code ONLINE}.</li>
     *   <li>Verificar que el operador no tenga ya una sesión activa. Si la
     *       tiene, el login es rechazado: debe cerrar la sesión anterior
     *       explícitamente antes de iniciar una nueva.</li>
     *   <li>Autenticar contra Cognito con {@code ADMIN_USER_PASSWORD_AUTH}.</li>
     *   <li>Limpiar sesiones expiradas (lazy) y registrar la nueva sesión.</li>
     * </ol>
     *
     * @param request   Credenciales y número de serie del terminal.
     * @param ipAddress IP del cliente POS para auditoría. Puede ser {@code null}.
     * @return tokens de Cognito listos para enviar al dispositivo.
     * @throws BadRequestException   si el RUT es inválido o falta el {@code serialNumber} para OPERATOR.
     * @throws NotFoundException     si el usuario o el terminal no existen en BD.
     * @throws ForbiddenException    si el usuario no está {@code ACTIVE}, el rol no es {@code OPERATOR} o el terminal no está {@code ONLINE}.
     * @throws ConflictException     si el operador ya tiene una sesión activa.
     * @throws UnauthorizedException si las credenciales son incorrectas según Cognito.
     */
    public TokenResponse deviceLogin(DeviceLoginRequest request, String ipAddress) {
        String rut = RutUtils.normalizeAndValidate(request.rut());

        // Paso 1-2: buscar usuario antes de llamar a Cognito
        User user = users.findByRut(rut)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));

        if (!"ACTIVE".equals(user.userStatus())) {
            throw new ForbiddenException("usuario_inactivo");
        }

        // Paso 3: este endpoint es exclusivo para OPERATOR
        if (!"OPERATOR".equals(user.role())) {
            throw new ForbiddenException("canal_no_autorizado");
        }

        // Paso 4: serial obligatorio para OPERATOR
        if (request.serialNumber() == null || request.serialNumber().isBlank()) {
            throw new BadRequestException("serial_number_requerido");
        }

        // Paso 5: terminal debe existir y estar ONLINE
        Terminal terminal = terminals.findBySerialNumber(request.serialNumber())
                .orElseThrow(() -> new NotFoundException("terminal_no_registrado"));

        if (!"ONLINE".equals(terminal.status())) {
            throw new ForbiddenException("terminal_no_disponible");
        }

        // Paso 6: el operador no puede tener una sesión activa en ningún terminal
        sessions.deleteExpired();
        if (sessions.findByUserId(user.id()).isPresent()) {
            throw new ConflictException("operador_ya_tiene_sesion_activa");
        }

        // Paso 7: autenticar contra Cognito (todas las validaciones de negocio ya pasaron)
        AdminInitiateAuthResponse authResponse = initiateAuthSafely(rut, request.password());

        // Paso 8: registrar sesiónsi
        sessions.upsert(user.id(), terminal.id(), ipAddress);

        return new TokenResponse(
                authResponse.authenticationResult().accessToken(),
                authResponse.authenticationResult().refreshToken(),
                authResponse.authenticationResult().idToken(),
                authResponse.authenticationResult().expiresIn()
        );
    }

    /**
     * Cierra la sesión del propio caller: invalida su refresh token en Cognito
     * y elimina su fila de {@code user_sessions}.
     *
     * <p>El access token actual del caller sigue siendo técnicamente válido
     * hasta su expiración natural (~1h), pero sin refresh token el dispositivo
     * no puede renovarlo. En la práctica el POS descartará el access token
     * cuando reciba la respuesta 200 de este endpoint.</p>
     *
     * <p>La revocación en Cognito es best-effort: si falla (ej: el token ya
     * expiró), la sesión en PostgreSQL se elimina igualmente.</p>
     *
     * @param cognitoSub  Sub del JWT del caller, extraído por Spring Security.
     * @param accessToken Bearer token del caller para llamar a {@code GlobalSignOut}.
     * @throws NotFoundException si el usuario no existe en PostgreSQL.
     */
    public void logout(String cognitoSub, String accessToken) {
        User user = users.findByCognitoSub(cognitoSub)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));

        try {
            cognito.globalSignOut(accessToken);
        } catch (Exception ignored) {
            // El token puede haber expirado; la eliminación de la sesión local procede igual
        }

        sessions.deleteByUserId(user.id());
    }

    /**
     * Fuerza el cierre de sesión de cualquier usuario. Solo accesible para ADMIN.
     *
     * <p>Llama a {@code AdminUserGlobalSignOut} en Cognito (requiere credenciales
     * IAM de servidor, no el token del usuario). Esto revoca todos los refresh
     * tokens del usuario. El acceso con su access token actual sigue funcionando
     * hasta que expire (~1h), lo cual es un límite conocido del protocolo OAuth2.</p>
     *
     * <p>La revocación en Cognito es best-effort para no bloquear la eliminación
     * de la sesión local si hay un problema transitorio con Cognito.</p>
     *
     * @param targetUserId UUID interno del usuario cuya sesión se cierra.
     * @throws NotFoundException si el usuario no existe en PostgreSQL.
     */
    public void adminCloseSession(UUID targetUserId) {
        User user = users.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));

        try {
            cognito.globalSignOutAdmin(user.rut());
        } catch (Exception ignored) {
            // Best-effort: si Cognito falla, la sesión local se elimina de todas formas
        }

        sessions.deleteByUserId(user.id());
    }

    /**
     * Llama a {@code AdminInitiateAuth} y traduce los errores de Cognito a
     * excepciones de dominio.
     *
     * <p>Tanto {@code UserNotFoundException} como {@code NotAuthorizedException}
     * se mapean al mismo mensaje genérico {@code "credenciales_invalidas"} para
     * no revelar al cliente si el usuario existe o la contraseña es incorrecta.</p>
     */
    private AdminInitiateAuthResponse initiateAuthSafely(String rut, String password) {
        try {
            return cognito.initiateAuth(rut, password);
        } catch (UserNotFoundException | NotAuthorizedException e) {
            throw new UnauthorizedException("credenciales_invalidas");
        }
    }
}
