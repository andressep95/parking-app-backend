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

type UpdateOrgRequest struct {
	OrgName    string `json:"org_name,omitempty"`
	RutEmpresa string `json:"rut_empresa,omitempty"`
	Email      string `json:"email,omitempty"`
	Phone      string `json:"phone_number,omitempty"`
}

// UpdateOrg PUT /api/v1/organizations/{id}
// ADMIN: cualquier organización. CUSTOMER: solo la suya.
func (h *Handler) UpdateOrg(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	groups := callerGroups(event)
	isAdmin := hasGroup(groups, "ADMIN")
	isCustomer := hasGroup(groups, "CUSTOMER")

	if !isAdmin && !isCustomer {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	id := event.PathParameters["id"]
	if id == "" {
		return jsonResponse(400, map[string]string{"error": "id_requerido"}), nil
	}

	if isCustomer && !isAdmin {
		oid, err := h.callerOrgID(ctx, callerSub(event))
		if err != nil {
			return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
		}
		if oid != id {
			return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
		}
	}

	var req UpdateOrgRequest
	if err := json.Unmarshal([]byte(event.Body), &req); err != nil {
		return jsonResponse(400, map[string]string{"error": "body_invalido"}), nil
	}

	update := expression.UpdateBuilder{}
	changed := false

	if req.OrgName != "" {
		update = update.Set(expression.Name("org_name"), expression.Value(req.OrgName))
		changed = true
	}
	if req.RutEmpresa != "" {
		update = update.Set(expression.Name("rut_empresa"), expression.Value(req.RutEmpresa))
		changed = true
	}
	if req.Email != "" {
		update = update.Set(expression.Name("org_email"), expression.Value(req.Email))
		changed = true
	}
	if req.Phone != "" {
		update = update.Set(expression.Name("phone_number"), expression.Value(req.Phone))
		changed = true
	}

	if !changed {
		return jsonResponse(400, map[string]string{"error": "sin_campos_para_actualizar"}), nil
	}

	expr, err := expression.NewBuilder().WithUpdate(update).Build()
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_interno"}), nil
	}

	_, err = h.dynamo.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "ORGANIZATION#" + id},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
		ConditionExpression:       aws.String("attribute_exists(PK)"),
		UpdateExpression:          expr.Update(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_actualizar"}), nil
	}

	return jsonResponse(200, map[string]string{"message": "organizacion_actualizada"}), nil
}
