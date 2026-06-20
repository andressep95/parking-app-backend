package main

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

type Handler struct {
	cognito    *cognitoidentityprovider.Client
	dynamo     *dynamodb.Client
	userPoolID string
	tableName  string
}

func (h *Handler) Route(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	switch event.RouteKey {
	case "GET /api/v1/users":
		return h.ListUsers(ctx, event)
	case "GET /api/v1/users/{id}":
		return h.GetUser(ctx, event)
	case "PUT /api/v1/users/{id}":
		return h.UpdateUser(ctx, event)
	case "POST /api/v1/users/{id}/activate":
		return h.SetUserStatus(ctx, event, true)
	case "POST /api/v1/users/{id}/deactivate":
		return h.SetUserStatus(ctx, event, false)
	case "POST /api/v1/users/{id}/reset-password":
		return h.ResetPassword(ctx, event)
	case "DELETE /api/v1/users/{id}":
		return h.DeleteUser(ctx, event)
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

// callerSub returns the Cognito sub of the authenticated caller.
func callerSub(event events.APIGatewayV2HTTPRequest) string {
	return event.RequestContext.Authorizer.JWT.Claims["sub"]
}

// callerGroups returns the raw cognito:groups claim (space-separated list).
func callerGroups(event events.APIGatewayV2HTTPRequest) string {
	return event.RequestContext.Authorizer.JWT.Claims["cognito:groups"]
}

// hasGroup checks if a group name appears in the cognito:groups claim.
func hasGroup(groups, group string) bool {
	return strings.Contains(groups, group)
}
