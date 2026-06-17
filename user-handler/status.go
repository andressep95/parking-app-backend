package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/expression"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// SetUserStatus POST /api/v1/users/{id}/activate|deactivate
// Requiere ADMIN o CUSTOMER.
func (h *Handler) SetUserStatus(ctx context.Context, event events.APIGatewayV2HTTPRequest, active bool) (events.APIGatewayV2HTTPResponse, error) {
	groups := callerGroups(event)
	if !hasGroup(groups, "ADMIN") && !hasGroup(groups, "CUSTOMER") {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	id := event.PathParameters["id"]

	u, err := h.fetchUser(ctx, id)
	if err != nil || u == nil {
		return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
	}

	newStatus := "ACTIVE"
	if !active {
		newStatus = "INACTIVE"
	}

	// Update DynamoDB
	update := expression.Set(expression.Name("user_status"), expression.Value(newStatus))
	expr, _ := expression.NewBuilder().WithUpdate(update).Build()

	_, err = h.dynamo.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "USER#" + id},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
		UpdateExpression:          expr.Update(),
		ExpressionAttributeNames:  expr.Names(),
		ExpressionAttributeValues: expr.Values(),
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_actualizar_estado"}), nil
	}

	// Mirror status in Cognito
	if active {
		_, err = h.cognito.AdminEnableUser(ctx, &cognitoidentityprovider.AdminEnableUserInput{
			UserPoolId: aws.String(h.userPoolID),
			Username:   aws.String(u.RUT),
		})
	} else {
		_, err = h.cognito.AdminDisableUser(ctx, &cognitoidentityprovider.AdminDisableUserInput{
			UserPoolId: aws.String(h.userPoolID),
			Username:   aws.String(u.RUT),
		})
	}
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_actualizar_cognito"}), nil
	}

	return jsonResponse(200, map[string]string{"status": newStatus}), nil
}
