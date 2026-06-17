package main

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

type Handler struct {
	dynamo    *dynamodb.Client
	tableName string
}

func (h *Handler) Route(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	switch event.RouteKey {
	case "GET /api/v1/organizations":
		return h.ListOrgs(ctx, event)
	case "GET /api/v1/organizations/{id}":
		return h.GetOrg(ctx, event)
	case "PUT /api/v1/organizations/{id}":
		return h.UpdateOrg(ctx, event)
	case "POST /api/v1/organizations/{id}/activate":
		return h.SetOrgStatus(ctx, event, true)
	case "POST /api/v1/organizations/{id}/deactivate":
		return h.SetOrgStatus(ctx, event, false)
	case "DELETE /api/v1/organizations/{id}":
		return h.DeleteOrg(ctx, event)
	default:
		return jsonResponse(404, map[string]string{"error": "not_found"}), nil
	}
}

func jsonResponse(statusCode int, body any) events.APIGatewayV2HTTPResponse {
	b, _ := json.Marshal(body)
	return events.APIGatewayV2HTTPResponse{
		StatusCode: statusCode,
		Headers:    map[string]string{"Content-Type": "application/json"},
		Body:       string(b),
	}
}

func callerSub(event events.APIGatewayV2HTTPRequest) string {
	return event.RequestContext.Authorizer.JWT.Claims["sub"]
}

func callerGroups(event events.APIGatewayV2HTTPRequest) string {
	return event.RequestContext.Authorizer.JWT.Claims["cognito:groups"]
}

func hasGroup(groups, group string) bool {
	return strings.Contains(groups, group)
}

// callerOrgID queries GSI1 (COGNITO#<sub>) to retrieve the caller's org_id from DynamoDB.
// Used to enforce CUSTOMER access to their own organization only.
func (h *Handler) callerOrgID(ctx context.Context, sub string) (string, error) {
	out, err := h.dynamo.Query(ctx, &dynamodb.QueryInput{
		TableName:              aws.String(h.tableName),
		IndexName:              aws.String("GSI1"),
		KeyConditionExpression: aws.String("GSI1PK = :pk"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":pk": &types.AttributeValueMemberS{Value: "COGNITO#" + sub},
		},
		Limit:                aws.Int32(1),
		ProjectionExpression: aws.String("org_id"),
	})
	if err != nil || len(out.Items) == 0 {
		return "", err
	}
	if v, ok := out.Items[0]["org_id"].(*types.AttributeValueMemberS); ok {
		return v.Value, nil
	}
	return "", nil
}
