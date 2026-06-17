package main

import (
	"context"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/google/uuid"
)

type UserItem struct {
	PK         string `dynamodbav:"PK"`
	SK         string `dynamodbav:"SK"`
	GSI1PK     string `dynamodbav:"GSI1PK"`
	GSI1SK     string `dynamodbav:"GSI1SK"`
	GSI2PK     string `dynamodbav:"GSI2PK"`
	GSI2SK     string `dynamodbav:"GSI2SK"`
	ID         string `dynamodbav:"id"`
	RUT        string `dynamodbav:"rut"`
	Email      string `dynamodbav:"email_addr"` // 'email' es palabra reservada en DynamoDB
	GivenName  string `dynamodbav:"given_name"`
	FamilyName string `dynamodbav:"family_name"`
	Phone      string `dynamodbav:"phone_number,omitempty"`
	Role       string `dynamodbav:"role"`
	Status     string `dynamodbav:"user_status"` // 'status' es palabra reservada en DynamoDB
	CustomerID string `dynamodbav:"customer_id,omitempty"`
	LocationID string `dynamodbav:"location_id,omitempty"`
	CognitoSub string `dynamodbav:"cognito_sub"`
	CreatedAt  string `dynamodbav:"created_at"`
}

func (h *Handler) writeUserRecord(ctx context.Context, cognitoSub, rut string, req RegisterRequest, role string) (string, error) {
	id := uuid.NewString()
	now := time.Now().UTC().Format(time.RFC3339)
	pk := "USER#" + id

	item := UserItem{
		PK:         pk,
		SK:         "#METADATA",
		GSI1PK:     "COGNITO#" + cognitoSub,
		GSI1SK:     pk,
		GSI2PK:     "EMAIL#" + req.Email,
		GSI2SK:     pk,
		ID:         id,
		RUT:        rut,
		Email:      req.Email,
		GivenName:  req.GivenName,
		FamilyName: req.FamilyName,
		Phone:      req.PhoneNumber,
		Role:       role,
		Status:     "ACTIVE",
		CustomerID: req.CustomerID,
		LocationID: req.LocationID,
		CognitoSub: cognitoSub,
		CreatedAt:  now,
	}

	av, err := attributevalue.MarshalMap(item)
	if err != nil {
		return "", err
	}

	transactItems := []types.TransactWriteItem{
		{
			Put: &types.Put{
				TableName:           aws.String(h.tableName),
				Item:                av,
				ConditionExpression: aws.String("attribute_not_exists(PK)"),
			},
		},
	}

	// Vincula el operador a su cliente en el mismo TransactWrite
	if req.CustomerID != "" && role == "CUSTOMER_OPERATOR" {
		link := map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "CUSTOMER#" + req.CustomerID},
			"SK": &types.AttributeValueMemberS{Value: "OPERATOR#" + id},
		}
		transactItems = append(transactItems, types.TransactWriteItem{
			Put: &types.Put{
				TableName: aws.String(h.tableName),
				Item:      link,
			},
		})
	}

	_, err = h.dynamo.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{
		TransactItems: transactItems,
	})
	return id, err
}
