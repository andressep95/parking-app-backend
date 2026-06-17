package main

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	cognitotypes "github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider/types"
	"github.com/andressep95/auth-handler/rut"
)

type RegisterRequest struct {
	RUT         string `json:"rut"`
	Password    string `json:"password"`
	Email       string `json:"email"`
	GivenName   string `json:"given_name"`
	FamilyName  string `json:"family_name"`
	PhoneNumber string `json:"phone_number,omitempty"`
	Role        string `json:"role,omitempty"` // ADMIN | CUSTOMER | CUSTOMER_OPERATOR (default)

	// Campos para operadores asignados a una organización existente
	OrgID      string `json:"org_id,omitempty"`
	LocationID string `json:"location_id,omitempty"`

	// Campos requeridos al crear una nueva organización (role == CUSTOMER)
	OrgName string `json:"org_name,omitempty"`
	OrgRut  string `json:"org_rut,omitempty"`
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

	role := req.Role
	if role == "" {
		role = "CUSTOMER_OPERATOR"
	}

	if role == "CUSTOMER" && req.OrgName == "" {
		return jsonResponse(400, map[string]string{"error": "org_name_requerido"}), nil
	}

	sub, err := h.createCognitoUser(ctx, normalized, req.Password, req.Email, req.GivenName, req.FamilyName, req.PhoneNumber)
	if err != nil {
		var exists *cognitotypes.UsernameExistsException
		if errors.As(err, &exists) {
			return jsonResponse(409, map[string]string{"error": "usuario_ya_existe"}), nil
		}
		return jsonResponse(500, map[string]string{"error": "error_al_crear_usuario"}), nil
	}

	// Asignar grupo Cognito (best-effort)
	_, _ = h.cognito.AdminAddUserToGroup(ctx, &cognitoidentityprovider.AdminAddUserToGroupInput{
		UserPoolId: aws.String(h.userPoolID),
		Username:   aws.String(normalized),
		GroupName:  aws.String(role),
	})

	result, err := h.writeUserRecord(ctx, sub, normalized, req, role)
	if err != nil {
		// rollback: eliminar usuario Cognito para no dejar huérfano
		_, _ = h.cognito.AdminDeleteUser(ctx, &cognitoidentityprovider.AdminDeleteUserInput{
			UserPoolId: aws.String(h.userPoolID),
			Username:   aws.String(normalized),
		})
		return jsonResponse(500, map[string]string{"error": "error_al_crear_usuario"}), nil
	}

	resp := map[string]string{
		"message": "usuario_creado",
		"id":      result.UserID,
	}
	if result.OrgID != "" {
		resp["org_id"] = result.OrgID
	}
	return jsonResponse(201, resp), nil
}

// createCognitoUser crea el usuario en Cognito y retorna su cognito_sub.
func (h *Handler) createCognitoUser(ctx context.Context, username, password, email, givenName, familyName, phone string) (string, error) {
	attrs := []cognitotypes.AttributeType{
		{Name: aws.String("email"),       Value: aws.String(email)},
		{Name: aws.String("given_name"),  Value: aws.String(givenName)},
		{Name: aws.String("family_name"), Value: aws.String(familyName)},
	}
	if phone != "" {
		attrs = append(attrs, cognitotypes.AttributeType{
			Name: aws.String("phone_number"), Value: aws.String(phone),
		})
	}

	out, err := h.cognito.AdminCreateUser(ctx, &cognitoidentityprovider.AdminCreateUserInput{
		UserPoolId:     aws.String(h.userPoolID),
		Username:       aws.String(username),
		MessageAction:  cognitotypes.MessageActionTypeSuppress,
		UserAttributes: attrs,
	})
	if err != nil {
		return "", err
	}

	var sub string
	for _, attr := range out.User.Attributes {
		if aws.ToString(attr.Name) == "sub" {
			sub = aws.ToString(attr.Value)
			break
		}
	}

	_, err = h.cognito.AdminSetUserPassword(ctx, &cognitoidentityprovider.AdminSetUserPasswordInput{
		UserPoolId: aws.String(h.userPoolID),
		Username:   aws.String(username),
		Password:   aws.String(password),
		Permanent:  true,
	})
	return sub, err
}
