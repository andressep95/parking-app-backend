package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// ListOrgs GET /api/v1/organizations — ADMIN only, scan all org metadata items.
func (h *Handler) ListOrgs(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	if !hasGroup(callerGroups(event), "ADMIN") {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	out, err := h.dynamo.Scan(ctx, &dynamodb.ScanInput{
		TableName:        aws.String(h.tableName),
		Limit:            aws.Int32(100),
		FilterExpression: aws.String("SK = :sk AND begins_with(PK, :prefix)"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":sk":     &types.AttributeValueMemberS{Value: "#METADATA"},
			":prefix": &types.AttributeValueMemberS{Value: "ORGANIZATION#"},
		},
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_listar"}), nil
	}

	orgs := make([]OrgResponse, 0, len(out.Items))
	for _, raw := range out.Items {
		var o OrgItem
		if err := attributevalue.UnmarshalMap(raw, &o); err == nil {
			orgs = append(orgs, toOrgResponse(o))
		}
	}

	return jsonResponse(200, orgs), nil
}
