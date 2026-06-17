package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// GetUser GET /api/v1/users/{id}
func (h *Handler) GetUser(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	id := event.PathParameters["id"]
	groups := callerGroups(event)

	u, err := h.fetchUser(ctx, id)
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_obtener_usuario"}), nil
	}
	if u == nil {
		return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
	}

	// CUSTOMER_OPERATOR can only see themselves
	if !hasGroup(groups, "ADMIN") && !hasGroup(groups, "CUSTOMER") {
		if u.CognitoSub != callerSub(event) {
			return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
		}
	}

	return jsonResponse(200, toResponse(*u)), nil
}

// fetchUser retrieves a UserItem by internal user ID.
func (h *Handler) fetchUser(ctx context.Context, id string) (*UserItem, error) {
	out, err := h.dynamo.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "USER#" + id},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
	})
	if err != nil {
		return nil, err
	}
	if out.Item == nil {
		return nil, nil
	}

	var u UserItem
	if err := attributevalue.UnmarshalMap(out.Item, &u); err != nil {
		return nil, err
	}
	return &u, nil
}
