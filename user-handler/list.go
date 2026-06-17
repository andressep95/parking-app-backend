package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// ListUsers GET /api/v1/users
// ADMIN puede listar todos (?org_id= opcional) o filtrar por organización.
// CUSTOMER debe pasar ?org_id= de su propia organización.
func (h *Handler) ListUsers(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	groups := callerGroups(event)
	isAdmin := hasGroup(groups, "ADMIN")
	isCustomer := hasGroup(groups, "CUSTOMER")

	if !isAdmin && !isCustomer {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	orgID := event.QueryStringParameters["org_id"]

	// Sin org_id solo ADMIN puede hacer scan (limitado a 50)
	if orgID == "" {
		if !isAdmin {
			return jsonResponse(400, map[string]string{"error": "org_id_requerido"}), nil
		}
		return h.scanAllUsers(ctx)
	}

	return h.listByOrg(ctx, orgID)
}

// listByOrg queries ORGANIZATION#<id>/OPERATOR#* links and batch-fetches user items.
func (h *Handler) listByOrg(ctx context.Context, orgID string) (events.APIGatewayV2HTTPResponse, error) {
	// Step 1: Query todos los links ORGANIZATION#<id>/OPERATOR#*
	qOut, err := h.dynamo.Query(ctx, &dynamodb.QueryInput{
		TableName:              aws.String(h.tableName),
		KeyConditionExpression: aws.String("PK = :pk AND begins_with(SK, :prefix)"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":pk":     &types.AttributeValueMemberS{Value: "ORGANIZATION#" + orgID},
			":prefix": &types.AttributeValueMemberS{Value: "OPERATOR#"},
		},
		ProjectionExpression: aws.String("SK"),
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_listar"}), nil
	}

	if len(qOut.Items) == 0 {
		return jsonResponse(200, []UserResponse{}), nil
	}

	// Step 2: Build BatchGetItem keys from OPERATOR#<user_id> SK values
	keys := make([]map[string]types.AttributeValue, 0, len(qOut.Items))
	for _, item := range qOut.Items {
		sk, ok := item["SK"].(*types.AttributeValueMemberS)
		if !ok {
			continue
		}
		userID := sk.Value[len("OPERATOR#"):] // strip prefix
		keys = append(keys, map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "USER#" + userID},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		})
	}

	bOut, err := h.dynamo.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{
		RequestItems: map[string]types.KeysAndAttributes{
			h.tableName: {Keys: keys},
		},
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_listar"}), nil
	}

	var users []UserResponse
	for _, raw := range bOut.Responses[h.tableName] {
		var u UserItem
		if err := attributevalue.UnmarshalMap(raw, &u); err == nil {
			users = append(users, toResponse(u))
		}
	}

	return jsonResponse(200, users), nil
}

// scanAllUsers does a limited scan for ADMIN use only (max 50 items).
func (h *Handler) scanAllUsers(ctx context.Context) (events.APIGatewayV2HTTPResponse, error) {
	out, err := h.dynamo.Scan(ctx, &dynamodb.ScanInput{
		TableName:        aws.String(h.tableName),
		Limit:            aws.Int32(50),
		FilterExpression: aws.String("SK = :sk"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":sk": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_listar"}), nil
	}

	var users []UserResponse
	for _, raw := range out.Items {
		var u UserItem
		if err := attributevalue.UnmarshalMap(raw, &u); err == nil {
			users = append(users, toResponse(u))
		}
	}

	return jsonResponse(200, users), nil
}
