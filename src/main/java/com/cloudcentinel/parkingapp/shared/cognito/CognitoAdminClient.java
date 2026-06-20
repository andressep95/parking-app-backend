package com.cloudcentinel.parkingapp.shared.cognito;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUserGlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wrapper de las operaciones administrativas de AWS Cognito usadas por este backend.
 *
 * <h2>Por qué existe este wrapper</h2>
 * <p>Ningún {@code @Service} del dominio importa el AWS SDK directamente.
 * Esta clase centraliza todas las llamadas a Cognito por dos razones:</p>
 * <ol>
 *   <li><b>Testeabilidad</b>: los tests unitarios de los services pueden
 *       mockear este bean sin levantar un user pool real.</li>
 *   <li><b>Cohesión</b>: si AWS cambia alguna API del SDK o necesitamos
 *       migrar de proveedor, el cambio queda localizado aquí.</li>
 * </ol>
 *
 * <h2>Flujos de autenticación</h2>
 * <p>El POS Android nunca habla directamente con Cognito. Este backend actúa
 * como proxy usando el flujo {@code ADMIN_USER_PASSWORD_AUTH}, que requiere
 * credenciales IAM del servidor y no expone los tokens de la app cliente.
 * Los usuarios ADMIN y CUSTOMER se autentican directamente desde el dashboard
 * web (fuera del alcance de este backend).</p>
 *
 * <h2>Responsabilidad de rollback</h2>
 * <p>Si la escritura en PostgreSQL falla después de crear el usuario en Cognito,
 * el service que llamó a {@link #createUser} debe llamar a {@link #deleteUser}
 * para no dejar usuarios huérfanos en el user pool. Este wrapper no gestiona
 * transacciones; esa responsabilidad es del service.</p>
 */
@Component
public class CognitoAdminClient {

    private final CognitoIdentityProviderClient client;
    private final String userPoolId;
    private final String clientId;

    public CognitoAdminClient(
            CognitoIdentityProviderClient client,
            @Value("${aws.cognito.user-pool-id}") String userPoolId,
            @Value("${aws.cognito.client-id}") String clientId) {
        this.client = client;
        this.userPoolId = userPoolId;
        this.clientId = clientId;
    }

    /**
     * Crea un usuario en el user pool de Cognito con contraseña permanente.
     *
     * <p>Usa {@code MessageAction.SUPPRESS} para que Cognito no envíe el correo
     * de bienvenida automático; el sistema gestiona la comunicación al usuario.</p>
     *
     * <p>La contraseña se establece como permanente con {@code AdminSetUserPassword}
     * inmediatamente después de la creación, evitando que el usuario quede en estado
     * {@code FORCE_CHANGE_PASSWORD} que bloquearía el login desde el POS.</p>
     *
     * @param username  RUT normalizado (ej: {@code 12345678-9}). Es el username en Cognito.
     * @param password  Contraseña en texto plano. Solo viaja por TLS backend → Cognito.
     * @param email     Atributo de perfil; no se usa como username de login.
     * @param givenName Nombre de pila.
     * @param familyName Apellido.
     * @param phone     Teléfono en formato E.164 (opcional, puede ser {@code null}).
     * @return {@code cognito_sub} del usuario recién creado, usado como FK en {@code users}.
     */
    public String createUser(String username, String password, String email,
                             String givenName, String familyName, String phone) {
        List<AttributeType> attrs = new ArrayList<>(List.of(
                attr("email",       email),
                attr("given_name",  givenName),
                attr("family_name", familyName)
        ));
        if (phone != null && !phone.isBlank()) {
            attrs.add(attr("phone_number", phone));
        }

        AdminCreateUserResponse response = client.adminCreateUser(
                AdminCreateUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(username)
                        .messageAction(MessageActionType.SUPPRESS)
                        .userAttributes(attrs)
                        .build()
        );

        // Establecer contraseña permanente para evitar el estado FORCE_CHANGE_PASSWORD
        client.adminSetUserPassword(
                AdminSetUserPasswordRequest.builder()
                        .userPoolId(userPoolId)
                        .username(username)
                        .password(password)
                        .permanent(true)
                        .build()
        );

        return response.user().attributes().stream()
                .filter(a -> "sub".equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Cognito no retornó sub para: " + username));
    }

    /**
     * Asigna el usuario al grupo Cognito que corresponde a su rol.
     *
     * <p>Los grupos Cognito son la fuente de verdad del rol dentro del JWT.
     * Spring Security los lee del claim {@code cognito:groups} y los mapea
     * a {@code ROLE_ADMIN}, {@code ROLE_CUSTOMER}, {@code ROLE_OPERATOR}.</p>
     *
     * @param username RUT normalizado del usuario.
     * @param group    Nombre del grupo: {@code ADMIN}, {@code CUSTOMER} o {@code OPERATOR}.
     */
    public void addUserToGroup(String username, String group) {
        client.adminAddUserToGroup(
                AdminAddUserToGroupRequest.builder()
                        .userPoolId(userPoolId)
                        .username(username)
                        .groupName(group)
                        .build()
        );
    }

    /**
     * Elimina un usuario de Cognito. Usado como rollback cuando falla el INSERT
     * en PostgreSQL tras una creación exitosa en Cognito.
     *
     * @param username RUT normalizado del usuario a eliminar.
     */
    public void deleteUser(String username) {
        client.adminDeleteUser(
                AdminDeleteUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(username)
                        .build()
        );
    }

    /**
     * Autentica un usuario desde el backend usando el flujo {@code ADMIN_USER_PASSWORD_AUTH}.
     *
     * <p>Este flujo requiere credenciales IAM del servidor (no expone el client secret
     * al cliente) y está diseñado para autenticación mediada, donde el backend actúa
     * como proxy entre el POS Android y Cognito.</p>
     *
     * <p>Diferencia clave con {@code InitiateAuth USER_PASSWORD_AUTH}: ese flujo puede
     * llamarse desde el cliente; este solo funciona con credenciales de servidor,
     * lo que impide que una app comprometida lo use directamente.</p>
     *
     * @param username RUT normalizado del operador.
     * @param password Contraseña en texto plano. Solo viaja por TLS POS → backend → Cognito.
     * @return Respuesta de Cognito con {@code AccessToken}, {@code RefreshToken} e {@code IdToken}.
     */
    public AdminInitiateAuthResponse initiateAuth(String username, String password) {
        return client.adminInitiateAuth(
                AdminInitiateAuthRequest.builder()
                        .userPoolId(userPoolId)
                        .clientId(clientId)
                        .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                        .authParameters(Map.of(
                                "USERNAME", username,
                                "PASSWORD", password
                        ))
                        .build()
        );
    }

    /**
     * Revoca todos los refresh tokens activos de un usuario de forma remota.
     *
     * <p>Se usa en dos escenarios:</p>
     * <ol>
     *   <li><b>Login con sesión previa</b>: antes de emitir nuevos tokens al OPERATOR,
     *       se invalidan los tokens de la sesión anterior para garantizar sesión única.</li>
     *   <li><b>Cierre remoto por ADMIN</b>: un administrador puede forzar el logout
     *       de cualquier usuario desde el dashboard web.</li>
     * </ol>
     *
     * <p>Nota: los access tokens ya emitidos siguen siendo válidos hasta su expiración
     * natural (~1h). Esta operación revoca solo el refresh token, impidiendo la
     * renovación pero no cortando la sesión activa de inmediato.</p>
     *
     * @param username RUT normalizado del usuario cuya sesión se revoca.
     */
    public void globalSignOutAdmin(String username) {
        client.adminUserGlobalSignOut(
                AdminUserGlobalSignOutRequest.builder()
                        .userPoolId(userPoolId)
                        .username(username)
                        .build()
        );
    }

    /**
     * Invalida el refresh token del propio caller usando su access token actual.
     *
     * <p>A diferencia de {@link #globalSignOutAdmin}, esta operación la ejecuta
     * el propio usuario con su token; no requiere permisos IAM de administrador.
     * Se usa en el endpoint {@code POST /auth/logout} donde el operador cierra
     * su propia sesión.</p>
     *
     * @param accessToken Bearer token del caller, extraído del header {@code Authorization}.
     */
    public void globalSignOut(String accessToken) {
        client.globalSignOut(
                GlobalSignOutRequest.builder()
                        .accessToken(accessToken)
                        .build()
        );
    }

    private static AttributeType attr(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }
}
