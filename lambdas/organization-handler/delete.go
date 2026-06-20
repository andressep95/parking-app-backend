package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// DeleteOrg DELETE /api/v1/organizations/{id} — ADMIN only.
// Elimina el item #METADATA y el link ORGANIZATION#<id>/USER#<admin_id>.
// Los links de operadores y sedes deben limpiarse por separado.
func (h *Handler) DeleteOrg(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	if !hasGroup(callerGroups(event), "ADMIN") {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	id := event.PathParameters["id"]
	if id == "" {
		return jsonResponse(400, map[string]string{"error": "id_requerido"}), nil
	}

	org, err := h.fetchOrg(ctx, id)
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_obtener"}), nil
	}
	if org == nil {
		return jsonResponse(404, map[string]string{"error": "organizacion_no_encontrada"}), nil
	}

	transactItems := []types.TransactWriteItem{
		// Eliminar item principal #METADATA
		{
			Delete: &types.Delete{
				TableName: aws.String(h.tableName),
				Key: map[string]types.AttributeValue{
					"PK": &types.AttributeValueMemberS{Value: "ORGANIZATION#" + id},
					"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
				},
			},
		},
		// Eliminar link ORGANIZATION#<id>/USER#<admin_id>
		{
			Delete: &types.Delete{
				TableName: aws.String(h.tableName),
				Key: map[string]types.AttributeValue{
					"PK": &types.AttributeValueMemberS{Value: "ORGANIZATION#" + id},
					"SK": &types.AttributeValueMemberS{Value: "USER#" + org.AdminUserID},
				},
			},
		},
	}

	_, err = h.dynamo.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{
		TransactItems: transactItems,
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_eliminar"}), nil
	}

	return jsonResponse(200, map[string]string{"message": "organizacion_eliminada"}), nil
}
