package main

import (
	"context"
	"encoding/json"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/expression"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

type UpdateUserRequest struct {
	Email      string `json:"email,omitempty"`
	GivenName  string `json:"given_name,omitempty"`
	FamilyName string `json:"family_name,omitempty"`
	Phone      string `json:"phone_number,omitempty"`
	LocationID string `json:"location_id,omitempty"`
}

// UpdateUser PUT /api/v1/users/{id}
func (h *Handler) UpdateUser(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	id := event.PathParameters["id"]
	groups := callerGroups(event)

	if !hasGroup(groups, "ADMIN") && !hasGroup(groups, "CUSTOMER") {
		// CUSTOMER_OPERATOR can only update themselves
		u, err := h.fetchUser(ctx, id)
		if err != nil || u == nil {
			return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
		}
		if u.CognitoSub != callerSub(event) {
			return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
		}
	}

	var req UpdateUserRequest
	if err := json.Unmarshal([]byte(event.Body), &req); err != nil {
		return jsonResponse(400, map[string]string{"error": "body_invalido"}), nil
	}

	update := expression.UpdateBuilder{}
	hasUpdate := false

	if req.Email != "" {
		update = update.Set(expression.Name("email_addr"), expression.Value(req.Email))
		hasUpdate = true
	}
	if req.GivenName != "" {
		update = update.Set(expression.Name("given_name"), expression.Value(req.GivenName))
		hasUpdate = true
	}
	if req.FamilyName != "" {
		update = update.Set(expression.Name("family_name"), expression.Value(req.FamilyName))
		hasUpdate = true
	}
	if req.Phone != "" {
		update = update.Set(expression.Name("phone_number"), expression.Value(req.Phone))
		hasUpdate = true
	}
	if req.LocationID != "" {
		update = update.Set(expression.Name("location_id"), expression.Value(req.LocationID))
		hasUpdate = true
	}

	if !hasUpdate {
		return jsonResponse(400, map[string]string{"error": "sin_campos_para_actualizar"}), nil
	}

	expr, err := expression.NewBuilder().WithUpdate(update).Build()
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
	}

	_, err = h.dynamo.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "USER#" + id},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
		UpdateExpression:          expr.Update(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
		ConditionExpression:       aws.String("attribute_exists(PK)"),
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_actualizar"}), nil
	}

	u, _ := h.fetchUser(ctx, id)
	if u == nil {
		return jsonResponse(200, map[string]string{"message": "actualizado"}), nil
	}
	return jsonResponse(200, toResponse(*u)), nil
}
