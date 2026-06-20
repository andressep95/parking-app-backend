package main

import (
	"context"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
)

// HandleLogout POST /api/v1/auth/logout
// Cierra la sesión activa del caller: invalida el refresh token en Cognito
// y elimina SESSION#ACTIVE en DynamoDB.
func (h *Handler) HandleLogout(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	sub := event.RequestContext.Authorizer.JWT.Claims["sub"]
	if sub == "" {
		return jsonResponse(401, map[string]string{"error": "no_autenticado"}), nil
	}

	userID, err := h.userIDBySub(ctx, sub)
	if err != nil || userID == "" {
		return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
	}

	// Invalidar refresh token via Cognito usando el access token del header
	if raw := event.Headers["authorization"]; raw != "" {
		token := strings.TrimPrefix(raw, "Bearer ")
		_, _ = h.cognito.GlobalSignOut(ctx, &cognitoidentityprovider.GlobalSignOutInput{
			AccessToken: aws.String(token),
		})
	}

	if err := h.deleteSessionActive(ctx, userID); err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_cerrar_sesion"}), nil
	}

	return jsonResponse(200, map[string]string{"message": "sesion_cerrada"}), nil
}

// HandleAdminCloseSession DELETE /api/v1/auth/sessions/{user_id}
// Solo ADMIN. Cierra la sesión activa de cualquier usuario de forma remota:
// invalida sus refresh tokens en Cognito y elimina SESSION#ACTIVE en DynamoDB.
// El access token actual del usuario sigue válido hasta su expiración natural (~1h).
func (h *Handler) HandleAdminCloseSession(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	if !strings.Contains(event.RequestContext.Authorizer.JWT.Claims["cognito:groups"], "ADMIN") {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	userID := event.PathParameters["user_id"]
	if userID == "" {
		return jsonResponse(400, map[string]string{"error": "user_id_requerido"}), nil
	}

	rut, err := h.userRUTByID(ctx, userID)
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
	}
	if rut == "" {
		return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
	}

	// Invalidar todos los refresh tokens del usuario en Cognito
	_, _ = h.cognito.AdminUserGlobalSignOut(ctx, &cognitoidentityprovider.AdminUserGlobalSignOutInput{
		UserPoolId: aws.String(h.userPoolID),
		Username:   aws.String(rut),
	})

	if err := h.deleteSessionActive(ctx, userID); err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_cerrar_sesion"}), nil
	}

	return jsonResponse(200, map[string]string{"message": "sesion_cerrada"}), nil
}
