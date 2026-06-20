package main

import (
	"context"
	"encoding/json"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
)

type ResetPasswordRequest struct {
	NewPassword string `json:"new_password"`
}

// ResetPassword POST /api/v1/users/{id}/reset-password
// Solo ADMIN puede forzar el cambio de contraseña de otro usuario.
func (h *Handler) ResetPassword(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	if !hasGroup(callerGroups(event), "ADMIN") {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	id := event.PathParameters["id"]

	var req ResetPasswordRequest
	if err := json.Unmarshal([]byte(event.Body), &req); err != nil || req.NewPassword == "" {
		return jsonResponse(400, map[string]string{"error": "new_password_requerido"}), nil
	}

	u, err := h.fetchUser(ctx, id)
	if err != nil || u == nil {
		return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
	}

	_, err = h.cognito.AdminSetUserPassword(ctx, &cognitoidentityprovider.AdminSetUserPasswordInput{
		UserPoolId: aws.String(h.userPoolID),
		Username:   aws.String(u.RUT),
		Password:   aws.String(req.NewPassword),
		Permanent:  true,
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_resetear_password"}), nil
	}

	return jsonResponse(200, map[string]string{"message": "password_actualizado"}), nil
}
