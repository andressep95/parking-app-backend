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

	// Verificar si el usuario es CUSTOMER_OPERATOR y validar el terminal
	isOperator, err := tokenHasGroup(accessToken, "CUSTOMER_OPERATOR")
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
	}

	if isOperator {
		if req.SerialNumber == "" {
			return jsonResponse(400, map[string]string{"error": "serial_number_requerido"}), nil
		}
		if err := h.validateTerminal(ctx, req.SerialNumber); err != nil {
			return jsonResponse(403, map[string]string{"error": err.Error()}), nil
		}
	}

	return jsonResponse(200, LoginResponse{
		AccessToken:  accessToken,
		IDToken:      aws.ToString(out.AuthenticationResult.IdToken),
		RefreshToken: aws.ToString(out.AuthenticationResult.RefreshToken),
		ExpiresIn:    out.AuthenticationResult.ExpiresIn,
	}), nil
}

// tokenHasGroup decodes the JWT payload (trusted, from Cognito response) and
// checks if the given group is present in the cognito:groups claim.
func tokenHasGroup(accessToken, group string) (bool, error) {
	parts := strings.SplitN(accessToken, ".", 3)
	if len(parts) != 3 {
		return false, errors.New("jwt malformado")
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return false, err
	}

	var claims map[string]any
	if err := json.Unmarshal(payload, &claims); err != nil {
		return false, err
	}

	// cognito:groups es un array de strings en el JWT
	raw, ok := claims["cognito:groups"]
	if !ok {
		return false, nil
	}
	groups, ok := raw.([]any)
	if !ok {
		return false, nil
	}
	for _, g := range groups {
		if s, ok := g.(string); ok && s == group {
			return true, nil
		}
	}
	return false, nil
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
