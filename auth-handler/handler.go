package main

import (
	"context"
	"encoding/json"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

// Handler holds shared clients and config for all endpoints.
type Handler struct {
	cognito    *cognitoidentityprovider.Client
	dynamo     *dynamodb.Client
	userPoolID string
	clientID   string
	tableName  string
}

// Route dispatches to the correct handler based on API Gateway HTTP v2 RouteKey.
func (h *Handler) Route(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	switch event.RouteKey {
	case "POST /api/v1/auth/login":
		return h.HandleLogin(ctx, event)
	case "POST /api/v1/auth/register":
		return h.HandleRegister(ctx, event)
	case "POST /api/v1/auth/register/batch":
		return h.HandleBatch(ctx, event)
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
