package main

import (
	"context"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// DeleteUser DELETE /api/v1/users/{id}
// Solo ADMIN puede eliminar usuarios. La operación elimina el item en DynamoDB y en Cognito.
func (h *Handler) DeleteUser(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	if !hasGroup(callerGroups(event), "ADMIN") {
		return jsonResponse(403, map[string]string{"error": "sin_permiso"}), nil
	}

	id := event.PathParameters["id"]

	u, err := h.fetchUser(ctx, id)
	if err != nil || u == nil {
		return jsonResponse(404, map[string]string{"error": "usuario_no_encontrado"}), nil
	}

	// Delete from DynamoDB
	_, err = h.dynamo.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "USER#" + id},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_eliminar"}), nil
	}

	// Delete from Cognito
	_, err = h.cognito.AdminDeleteUser(ctx, &cognitoidentityprovider.AdminDeleteUserInput{
		UserPoolId: aws.String(h.userPoolID),
		Username:   aws.String(u.RUT),
	})
	if err != nil {
		return jsonResponse(500, map[string]string{"error": "error_al_eliminar_cognito"}), nil
	}

	return jsonResponse(200, map[string]string{"message": "usuario_eliminado"}), nil
}
