package com.cloudcentinel.parkingapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * Registra el cliente de administración de AWS Cognito como bean singleton.
 *
 * <p>El cliente se construye una sola vez porque su inicialización implica
 * cargar credenciales IAM, resolver el endpoint regional y configurar el
 * pool de conexiones HTTP/2. Instanciarlo por cada operación sería costoso
 * y generaría fugas de recursos.</p>
 *
 * <p>Las credenciales se resuelven con {@link DefaultCredentialsProvider}, que
 * inspecciona en orden: variables de entorno ({@code AWS_ACCESS_KEY_ID} /
 * {@code AWS_SECRET_ACCESS_KEY}), perfil de instancia EC2/ECS y archivo
 * {@code ~/.aws/credentials}. En producción se espera que vengan del rol
 * IAM adjunto al servidor; en local, del archivo de credenciales o variables
 * de entorno del desarrollador.</p>
 */
@Configuration
public class CognitoAdminConfig {

    @Value("${aws.cognito.region:us-east-1}")
    private String region;

    /**
     * Cliente HTTP/2 para las operaciones administrativas de Cognito
     * (AdminCreateUser, AdminInitiateAuth, AdminUserGlobalSignOut, etc.).
     *
     * <p>Este bean es consumido exclusivamente por
     * {@code shared.cognito.CognitoAdminClient}. Ningún {@code @Service}
     * lo inyecta directamente para mantener el aislamiento del SDK.</p>
     */
    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
