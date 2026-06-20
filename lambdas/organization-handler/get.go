package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// GetOrg GET /api/v1/organizations/{id}
// ADMIN: cualquier organización. CUSTOMER: solo la suya (verificado vía GSI1).
func (h *Handler) GetOrg(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
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

	org, err := h.fetchOrg(ctx, id)
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_obtener"}), nil
	}
	if org == nil {
		return jsonResponse(404, map[string]string{"error": "organizacion_no_encontrada"}), nil
	}

	return jsonResponse(200, toOrgResponse(*org)), nil
}

func (h *Handler) fetchOrg(ctx context.Context, id string) (*OrgItem, error) {
	out, err := h.dynamo.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "ORGANIZATION#" + id},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
	})
	if err != nil {
		return nil, err
	}
	if out.Item == nil {
		return nil, nil
	}
	var o OrgItem
	if err := attributevalue.UnmarshalMap(out.Item, &o); err != nil {
		return nil, err
	}
	return &o, nil
}
