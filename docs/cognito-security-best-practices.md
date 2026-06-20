# Cognito como IdP — Seguridad y RBAC para parking-app

Stack objetivo: **AWS Cognito** (IdP) + **Spring Boot 3** + **PostgreSQL** en VPS Contabo.

---

## Tabla de contenidos

- [Arquitectura de roles (RBAC)](#arquitectura-de-roles-rbac)
- [Configuración del User Pool](#configuración-del-user-pool)
- [App Clients — flujos de autenticación](#app-clients--flujos-de-autenticación)
- [Validación JWT en Spring Boot](#validación-jwt-en-spring-boot)
- [Seguridad de tokens y revocación](#seguridad-de-tokens-y-revocación)
- [Protección contra fuerza bruta](#protección-contra-fuerza-bruta)
- [Checklist de implementación](#checklist-de-implementación)

---

## Arquitectura de roles (RBAC)

### Grupos Cognito vs `custom:role`

Usar **grupos Cognito** como fuente de verdad para RBAC, no `custom:role`. Los grupos viajan en el claim `cognito:groups` del JWT y Spring Security los lee directamente sin base de datos.

| Mecanismo | Uso correcto |
|---|---|
| **Grupos Cognito** | Autorización en Spring Security (`hasRole`, `@PreAuthorize`) |
| `custom:role` | Datos complementarios en la app (display, lógica de negocio) |

### Grupos a crear en el User Pool

```
ADMIN              → Personal interno de Haulmer
CUSTOMER           → Dueño/admin de una organización de estacionamiento
OPERATOR  → Cajero/operador en terminal TUU
```

Cada usuario pertenece a exactamente **un grupo**. La creación del usuario debe asignar el grupo en el mismo paso.

### Precedencia en Spring Security

Cognito emite `cognito:groups` como array en el access token. Spring Security lee este claim y lo convierte en roles. Si `custom:role` difiere del grupo Cognito, **el grupo tiene precedencia** porque es el que se valida criptográficamente en el JWT.

---

## Configuración del User Pool

### Password policy recomendada

```hcl
# Terraform — aws_cognito_user_pool
password_policy {
  minimum_length                   = 10
  require_lowercase                = true
  require_uppercase                = true
  require_numbers                  = true
  require_symbols                  = false   # evitar fricción en operadores TUU
  temporary_password_validity_days = 3
}
```

> Para operadores de estacionamiento (OPERATOR), la contraseña temporal de 3 días fuerza el cambio sin dejar cuentas huérfanas.

### Token lifetimes

| Token | Duración configurada | Observación |
|---|---|---|
| Access token | 1 hora | Correcto — es el token que valida cada request |
| Refresh token | 30 días | Aceptable para terminales TUU de uso diario |
| ID token | 1 hora | Se ignora en la API (ver sección JWT) |

Para terminales TUU con uso diario sostenido, 30 días de refresh token evita re-logins frecuentes.

### Atributos del User Pool

```hcl
schema {
  name                = "role"
  attribute_data_type = "String"
  mutable             = true
}
```

Atributos estándar requeridos: `email`, `given_name`, `family_name`.
Atributo estándar opcional: `phone_number`.
Atributo custom: `custom:role` (referencia interna, no usar para autorizar).

### Advanced Security (Cognito Threat Protection)

Para **100 usuarios** no es necesario activar Advanced Security ($0.05/MAU adicional). Activar cuando:
- La app tenga > 500 MAUs
- Se detecten intentos de credential stuffing
- Se necesite anomaly detection geográfico

---

## App Clients — flujos de autenticación

### App client para la app móvil / terminal TUU

```hcl
explicit_auth_flows = [
  "ALLOW_USER_SRP_AUTH",    # flujo seguro — password nunca viaja en claro
  "ALLOW_REFRESH_TOKEN_AUTH"
]
generate_secret = false     # apps móviles/nativas no pueden proteger un secret
```

**No activar `ALLOW_USER_PASSWORD_AUTH`** — envía la contraseña en texto plano al servidor Cognito. `USER_SRP_AUTH` usa Secure Remote Password (protocolo challenge-response, la contraseña nunca sale del cliente).

### App client para comunicación server-to-server (Spring Boot → Cognito Admin API)

```hcl
explicit_auth_flows = [
  "ALLOW_ADMIN_USER_PASSWORD_AUTH"  # solo para operaciones admin desde el backend
]
generate_secret = true              # el backend puede proteger el client secret
```

El Spring Boot backend usa el **AWS SDK** (no el app client) para operaciones admin (`AdminCreateUser`, `AdminSetUserPassword`, `AdminUserGlobalSignOut`). El client secret se almacena como variable de entorno en el VPS, nunca en código.

---

## Validación JWT en Spring Boot

### Dependencia

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

### Configuración `application.yml`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://cognito-idp.us-east-1.amazonaws.com/${COGNITO_USER_POOL_ID}
          # Spring descarga automáticamente las JWKS (claves públicas) desde:
          # {issuer-uri}/.well-known/jwks.json
```

### Converter de roles desde `cognito:groups`

Por defecto Spring Security no sabe que `cognito:groups` contiene los roles. Se necesita un converter custom:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/organizations/**").hasAnyRole("ADMIN", "CUSTOMER")
                .requestMatchers("/api/v1/terminal/**").hasRole("OPERATOR")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(cognitoJwtConverter()))
            );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter cognitoJwtConverter() {
        var converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("cognito:groups");  // claim del grupo
        converter.setAuthorityPrefix("ROLE_");                // Spring espera ROLE_ prefix

        var jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(converter);
        return jwtConverter;
    }
}
```

### Validación del claim `token_use`

Cognito emite dos tipos de tokens: `access` e `id`. La API **solo debe aceptar access tokens**. Agregar un validator custom:

```java
@Bean
public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
    var decoder = JwtDecoders.fromIssuerLocation(
        properties.getJwt().getIssuerUri()
    );

    var validator = new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(properties.getJwt().getIssuerUri()),
        jwt -> {
            String tokenUse = jwt.getClaimAsString("token_use");
            if (!"access".equals(tokenUse)) {
                return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Se requiere access token, no id token", null)
                );
            }
            return OAuth2TokenValidatorResult.success();
        }
    );

    decoder.setJwtValidator(validator);
    return decoder;
}
```

### Uso en controllers

```java
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    // Solo ADMIN puede crear organizaciones
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrganizationResponse> create(...) { ... }

    // ADMIN ve todas; CUSTOMER solo la suya (lógica en servicio)
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<OrganizationResponse> getById(...) { ... }
}
```

### Extracción de claims del token en el servicio

El access token contiene `sub` y `cognito:groups`. Para datos de negocio como `org_id`, se consulta la BD usando el `sub` del token — lookup O(1) con el índice `idx_users_cognito_sub`.

```java
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final UserRepository userRepository;

    public OrganizationResponse getById(UUID id, Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        String cognitoSub = jwt.getSubject();

        // Lookup por cognito_sub — índice UNIQUE, O(1)
        User currentUser = userRepository.findByCognitoSub(cognitoSub)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        // CUSTOMER solo puede ver su propia organización
        if (currentUser.getRole() == UserRole.CUSTOMER) {
            if (!id.equals(currentUser.getOrgId())) {
                throw new AccessDeniedException("Acceso denegado");
            }
        }
        // ...
    }
}
```

---

## Seguridad de tokens y revocación

### Logout desde el cliente (revocación del refresh token)

```java
// AuthService.java
public void logout(String refreshToken) {
    cognitoClient.revokeToken(RevokeTokenRequest.builder()
        .token(refreshToken)
        .clientId(cognitoClientId)
        .build()
    );
    // Eliminar UserSession de PostgreSQL
    userSessionRepository.deleteByUserId(currentUserId);
}
```

> `RevokeToken` invalida el refresh token en Cognito. El access token vigente sigue válido hasta expirar (máx 1h) — aceptable para esta escala. Si se necesita revocación inmediata del access token, implementar una blocklist en Redis/PostgreSQL con el `jti` claim.

### Cierre de sesión remoto (AP10b — ADMIN cierra sesión de otro usuario)

```java
public void forceLogout(String userId) {
    // 1. Invalidar todos los tokens Cognito del usuario
    cognitoClient.adminUserGlobalSignOut(AdminUserGlobalSignOutRequest.builder()
        .userPoolId(userPoolId)
        .username(userId)
        .build()
    );
    // 2. Eliminar UserSession de PostgreSQL
    userSessionRepository.deleteByUserId(UUID.fromString(userId));
}
```

`AdminUserGlobalSignOut` invalida todos los refresh tokens activos del usuario. Los access tokens existentes expiran en máx 1h.

### Rotación de refresh tokens

Activar en el app client:

```hcl
refresh_token_validity = 30  # días
token_validity_units {
  refresh_token = "days"
}
```

Cognito no hace rotación automática de refresh tokens (a diferencia de algunos otros IdPs). La renovación se hace explícitamente llamando a `InitiateAuth` con `REFRESH_TOKEN_AUTH`.

---

## Protección contra fuerza bruta

### Comportamiento nativo de Cognito (sin costo adicional)

Cognito bloquea automáticamente cuentas tras intentos fallidos repetidos con lockout progresivo:

| Intentos fallidos | Comportamiento |
|---|---|
| 1-4 | Sin bloqueo — solo registra |
| 5 | Cooldown de 1 segundo |
| Siguientes | Cooldown exponencial (2s, 4s, 8s...) |
| Persistentes | Cuenta bloqueada temporalmente (`UserNotConfirmedException` o `NotAuthorizedException`) |

Para **100 usuarios** esto es suficiente. No se necesita Cognito Advanced Security (Adaptive Authentication).

### Recomendación adicional en Spring Boot

Registrar intentos fallidos en `audit_logs` para tener visibilidad:

```java
// En el handler de AuthenticationFailureBadCredentialsEvent
@EventListener
public void onAuthFailure(AbstractAuthenticationFailureEvent event) {
    auditLogService.log(
        AuditAction.AUTH_FAILURE,
        "user",
        event.getAuthentication().getName(),
        Map.of("reason", event.getException().getMessage()),
        request.getRemoteAddr()
    );
}
```

---

## Checklist de implementación

### Cognito (Terraform / consola)

- [ ] Crear User Pool con username = RUT (campo libre)
- [ ] Configurar password policy (mínimo 10 chars, upper+lower+números)
- [ ] Crear custom attributes `custom:role` y `custom:org_id` (mutable, solo escritura via AWS SDK)
- [ ] Crear grupos: `ADMIN`, `CUSTOMER`, `OPERATOR`
- [ ] Crear app client: `USER_SRP_AUTH` + `REFRESH_TOKEN_AUTH`, sin secret
- [ ] Activar token revocation en el app client

### Spring Boot

- [ ] Agregar dependencia `spring-boot-starter-oauth2-resource-server`
- [ ] Configurar `issuer-uri` en `application.yml`
- [ ] Implementar `CognitoJwtConverter` (extrae `cognito:groups` → `ROLE_*`)
- [ ] Agregar validator de `token_use: "access"`
- [ ] Configurar `SecurityFilterChain` con rutas por rol
- [ ] Usar `@PreAuthorize` en controllers
- [ ] Extraer `sub` del JWT → lookup en tabla `users` por `cognito_sub` (índice UNIQUE)
- [ ] Implementar endpoint de logout que llame a `RevokeToken` Cognito
- [ ] Implementar `AdminUserGlobalSignOut` para cierre de sesión remoto (AP10b)

### Operativo (VPS)

- [ ] `COGNITO_USER_POOL_ID` y `COGNITO_CLIENT_ID` como variables de entorno (no en código)
- [ ] Credenciales AWS (para SDK admin) como variables de entorno o IAM role del VPS
- [ ] Rotar client id si se sospecha compromiso (`aws cognito-idp create-user-pool-client`)

---

## Referencia de claims en el JWT

El access token de Cognito contiene nativamente:

```json
{
  "sub": "us-east-1_abc|xyz",
  "cognito:groups": ["CUSTOMER"],
  "token_use": "access",
  "scope": "aws.cognito.signin.user.admin",
  "iss": "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXX",
  "exp": 1750000000,
  "iat": 1749996400,
  "username": "76543210-K"
}
```

Spring Security usa `cognito:groups` para autorización (`ROLE_CUSTOMER`, `ROLE_ADMIN`, `ROLE_OPERATOR`). El `sub` se usa para resolver el usuario en PostgreSQL (`users.cognito_sub`) cuando se necesitan datos de negocio como `org_id`.
