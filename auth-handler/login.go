package main

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider/types"
	"github.com/andressep95/auth-handler/rut"
)

type LoginRequest struct {
	RUT      string `json:"rut"`
	Password string `json:"password"`
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
		AuthFlow: types.AuthFlowTypeUserPasswordAuth,
		ClientId: aws.String(h.clientID),
		AuthParameters: map[string]string{
			"USERNAME": normalized,
			"PASSWORD": req.Password,
		},
	})
	if err != nil {
		var notFound *types.UserNotFoundException
		var notAuth *types.NotAuthorizedException
		switch {
		case errors.As(err, &notFound):
			return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
		case errors.As(err, &notAuth):
			return jsonResponse(401, map[string]string{"error": "credenciales_invalidas"}), nil
		default:
			return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
		}
	}

	return jsonResponse(200, LoginResponse{
		AccessToken:  aws.ToString(out.AuthenticationResult.AccessToken),
		IDToken:      aws.ToString(out.AuthenticationResult.IdToken),
		RefreshToken: aws.ToString(out.AuthenticationResult.RefreshToken),
		ExpiresIn:    out.AuthenticationResult.ExpiresIn,
	}), nil
}
