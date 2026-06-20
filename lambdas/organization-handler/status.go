package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// SetOrgStatus POST /api/v1/organizations/{id}/activate|deactivate — ADMIN only.
func (h *Handler) SetOrgStatus(ctx context.Context, event events.APIGatewayV2HTTPRequest, active bool) (events.APIGatewayV2HTTPResponse, error) {
	if !hasGroup(callerGroups(event), "ADMIN") {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	id := event.PathParameters["id"]
	if id == "" {
		return jsonResponse(400, map[string]string{"error": "id_requerido"}), nil
	}

	newStatus := "INACTIVE"
	if active {
		newStatus = "ACTIVE"
	}

	_, err := h.dynamo.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "ORGANIZATION#" + id},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
		ConditionExpression: aws.String("attribute_exists(PK)"),
		UpdateExpression:    aws.String("SET org_status = :s"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":s": &types.AttributeValueMemberS{Value: newStatus},
		},
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_actualizar_estado"}), nil
	}

	msg := "organizacion_desactivada"
	if active {
		msg = "organizacion_activada"
	}
	return jsonResponse(200, map[string]string{"message": msg}), nil
}
