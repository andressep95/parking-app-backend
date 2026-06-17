package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	cognitotypes "github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider/types"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/andressep95/auth-handler/rut"
)

type LoginRequest struct {
	RUT          string `json:"rut"`
	Password     string `json:"password"`
	SerialNumber string `json:"serial_number,omitempty"` // requerido para CUSTOMER_OPERATOR
}

type LoginResponse struct {
	AccessToken  string `json:"access_token"`
	IDToken      string `json:"id_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int32  `json:"expires_in"`
}

func (h *Handler) HandleLogin(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	var req LoginRequest
	if err := json.Unmarshal([]byte(event.Body), &req); err != nil {
		return jsonResponse(400, map[string]string{"error": "body_invalido"}), nil
	}

	normalized, err := rut.Normalize(req.RUT)
	if err != nil || !rut.Validate(normalized) {
		return jsonResponse(400, map[string]string{"error": "rut_invalido"}), nil
	}

	out, err := h.cognito.InitiateAuth(ctx, &cognitoidentityprovider.InitiateAuthInput{
		AuthFlow: cognitotypes.AuthFlowTypeUserPasswordAuth,
		ClientId: aws.String(h.clientID),
		AuthParameters: map[string]string{
			"USERNAME": normalized,
			"PASSWORD": req.Password,
		},
	})
	if err != nil {
		var notFound *cognitotypes.UserNotFoundException
		var notAuth *cognitotypes.NotAuthorizedException
		switch {
		case errors.As(err, &notFound):
			return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
		case errors.As(err, &notAuth):
			return jsonResponse(401, map[string]string{"error": "credenciales_invalidas"}), nil
		default:
			return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
		}
	}

	accessToken := aws.ToString(out.AuthenticationResult.AccessToken)

	// Decodificar claims del JWT para obtener sub y grupos en un solo paso
	claims, err := jwtClaims(accessToken)
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
	}

	sub, _ := claims["sub"].(string)

	isOperator := false
	if groups, ok := claims["cognito:groups"].([]any); ok {
		for _, g := range groups {
			if s, ok := g.(string); ok && s == "CUSTOMER_OPERATOR" {
				isOperator = true
				break
			}
		}
	}

	if isOperator {
		if req.SerialNumber == "" {
			return jsonResponse(400, map[string]string{"error": "serial_number_requerido"}), nil
		}
		if err := h.validateTerminal(ctx, req.SerialNumber); err != nil {
			return jsonResponse(403, map[string]string{"error": err.Error()}), nil
		}
	}

	// Registrar sesión activa en DynamoDB (best-effort — no bloquea el login)
	if sub != "" {
		userID, _ := h.userIDBySub(ctx, sub)
		if userID != "" {
			_ = h.writeSessionActive(ctx, userID, "")
		}
	}

	return jsonResponse(200, LoginResponse{
		AccessToken:  accessToken,
		IDToken:      aws.ToString(out.AuthenticationResult.IdToken),
		RefreshToken: aws.ToString(out.AuthenticationResult.RefreshToken),
		ExpiresIn:    out.AuthenticationResult.ExpiresIn,
	}), nil
}

// jwtClaims decodifica el payload del JWT (sin verificar firma — confiamos en el token de Cognito).
func jwtClaims(accessToken string) (map[string]any, error) {
	parts := strings.SplitN(accessToken, ".", 3)
	if len(parts) != 3 {
		return nil, errors.New("jwt malformado")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, err
	}
	var claims map[string]any
	return claims, json.Unmarshal(payload, &claims)
}

// validateTerminal verifica que el terminal con ese serial_number exista en DynamoDB
// y no esté desactivado. Usa GSI1 con SERIAL#<serial_number> (AP09).
func (h *Handler) validateTerminal(ctx context.Context, serialNumber string) error {
	out, err := h.dynamo.Query(ctx, &dynamodb.QueryInput{
		TableName:              aws.String(h.tableName),
		IndexName:              aws.String("GSI1"),
		KeyConditionExpression: aws.String("GSI1PK = :serial"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":serial": &types.AttributeValueMemberS{Value: "SERIAL#" + serialNumber},
		},
		Limit: aws.Int32(1),
	})
	if err != nil {
		return errors.New("error_al_validar_terminal")
	}
	if len(out.Items) == 0 {
		return errors.New("terminal_no_registrado")
	}

	// Rechazar terminales explícitamente desactivados (Terminal usa "status", no "user_status")
	statusAttr, hasStatus := out.Items[0]["status"]
	if hasStatus {
		if sv, ok := statusAttr.(*types.AttributeValueMemberS); ok && sv.Value == "INACTIVE" {
			return errors.New("terminal_inactivo")
		}
	}

	return nil
}
