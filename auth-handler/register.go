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

type RegisterRequest struct {
	RUT         string `json:"rut"`
	Password    string `json:"password"`
	Email       string `json:"email"`
	GivenName   string `json:"given_name"`
	FamilyName  string `json:"family_name"`
	PhoneNumber string `json:"phone_number,omitempty"`
}

func (h *Handler) HandleRegister(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	var req RegisterRequest
	if err := json.Unmarshal([]byte(event.Body), &req); err != nil {
		return jsonResponse(400, map[string]string{"error": "body_invalido"}), nil
	}

	normalized, err := rut.Normalize(req.RUT)
	if err != nil || !rut.Validate(normalized) {
		return jsonResponse(400, map[string]string{"error": "rut_invalido"}), nil
	}

	if req.Email == "" || req.GivenName == "" || req.FamilyName == "" || req.Password == "" {
		return jsonResponse(400, map[string]string{"error": "campos_requeridos"}), nil
	}

	return h.createUser(ctx, normalized, req.Password, req.Email, req.GivenName, req.FamilyName, req.PhoneNumber)
}

// createUser is shared by HandleRegister and HandleBatch.
func (h *Handler) createUser(ctx context.Context, username, password, email, givenName, familyName, phone string) (events.APIGatewayV2HTTPResponse, error) {
	attrs := []types.AttributeType{
		{Name: aws.String("email"),       Value: aws.String(email)},
		{Name: aws.String("given_name"),  Value: aws.String(givenName)},
		{Name: aws.String("family_name"), Value: aws.String(familyName)},
	}
	if phone != "" {
		attrs = append(attrs, types.AttributeType{
			Name:  aws.String("phone_number"),
			Value: aws.String(phone),
		})
	}

	_, err := h.cognito.AdminCreateUser(ctx, &cognitoidentityprovider.AdminCreateUserInput{
		UserPoolId:     aws.String(h.userPoolID),
		Username:       aws.String(username),
		MessageAction:  types.MessageActionTypeSuppress,
		UserAttributes: attrs,
	})
	if err != nil {
		var exists *types.UsernameExistsException
		if errors.As(err, &exists) {
			return jsonResponse(409, map[string]string{"error": "usuario_ya_existe"}), nil
		}
		return jsonResponse(500, map[string]string{"error": "error_al_crear_usuario"}), nil
	}

	_, err = h.cognito.AdminSetUserPassword(ctx, &cognitoidentityprovider.AdminSetUserPasswordInput{
		UserPoolId: aws.String(h.userPoolID),
		Username:   aws.String(username),
		Password:   aws.String(password),
		Permanent:  true,
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_setear_password"}), nil
	}

	return jsonResponse(201, map[string]string{"message": "usuario_creado"}), nil
}
