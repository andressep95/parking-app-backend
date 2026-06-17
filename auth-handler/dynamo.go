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

// UserItem mirrors the DynamoDB USER#<id>/#METADATA item.
// Attribute names avoid DynamoDB reserved words: email→email_addr, status→user_status.
type UserItem struct {
	PK         string `dynamodbav:"PK"`
	SK         string `dynamodbav:"SK"`
	GSI1PK     string `dynamodbav:"GSI1PK"`
	GSI1SK     string `dynamodbav:"GSI1SK"`
	GSI2PK     string `dynamodbav:"GSI2PK"`
	GSI2SK     string `dynamodbav:"GSI2SK"`
	ID         string `dynamodbav:"id"`
	RUT        string `dynamodbav:"rut"`
	Email      string `dynamodbav:"email_addr"`  // 'email' es reservada en DynamoDB
	GivenName  string `dynamodbav:"given_name"`
	FamilyName string `dynamodbav:"family_name"`
	Phone      string `dynamodbav:"phone_number,omitempty"`
	Role       string `dynamodbav:"role"`
	Status     string `dynamodbav:"user_status"` // 'status' es reservada en DynamoDB
	OrgID      string `dynamodbav:"org_id,omitempty"`
	LocationID string `dynamodbav:"location_id,omitempty"`
	CognitoSub string `dynamodbav:"cognito_sub"`
	CreatedAt  string `dynamodbav:"created_at"`
}

// OrgItem mirrors the DynamoDB ORGANIZATION#<id>/#METADATA item.
// 'name' y 'status' son reservadas en DynamoDB.
type OrgItem struct {
	PK          string `dynamodbav:"PK"`
	SK          string `dynamodbav:"SK"`
	ID          string `dynamodbav:"id"`
	OrgName     string `dynamodbav:"org_name"`
	RutEmpresa  string `dynamodbav:"rut_empresa,omitempty"`
	Email       string `dynamodbav:"org_email,omitempty"`
	Phone       string `dynamodbav:"phone_number,omitempty"`
	Status      string `dynamodbav:"org_status"`
	AdminUserID string `dynamodbav:"admin_user_id"`
	CreatedAt   string `dynamodbav:"created_at"`
}

// writeResult contiene los IDs generados por writeUserRecord.
type writeResult struct {
	UserID string
	OrgID  string // vacío si el rol no es CUSTOMER
}

// writeUserRecord escribe el usuario en DynamoDB.
// Si role == "CUSTOMER" también crea la organización y el link ORGANIZATION#/USER#.
// Si role == "CUSTOMER_OPERATOR" con OrgID, crea el link ORGANIZATION#/OPERATOR#.
func (h *Handler) writeUserRecord(ctx context.Context, cognitoSub, rut string, req RegisterRequest, role string) (writeResult, error) {
	userID := uuid.NewString()
	now := time.Now().UTC().Format(time.RFC3339)
	userPK := "USER#" + userID

	user := UserItem{
		PK:         userPK,
		SK:         "#METADATA",
		GSI1PK:     "COGNITO#" + cognitoSub,
		GSI1SK:     userPK,
		GSI2PK:     "EMAIL#" + req.Email,
		GSI2SK:     userPK,
		ID:         userID,
		RUT:        rut,
		Email:      req.Email,
		GivenName:  req.GivenName,
		FamilyName: req.FamilyName,
		Phone:      req.PhoneNumber,
		Role:       role,
		Status:     "ACTIVE",
		OrgID:      req.OrgID,
		LocationID: req.LocationID,
		CognitoSub: cognitoSub,
		CreatedAt:  now,
	}

	userAV, err := attributevalue.MarshalMap(user)
	if err != nil {
		return writeResult{}, err
	}

	transactItems := []types.TransactWriteItem{
		{
			Put: &types.Put{
				TableName:           aws.String(h.tableName),
				Item:                userAV,
				ConditionExpression: aws.String("attribute_not_exists(PK)"),
			},
		},
	}

	var orgID string

	switch role {
	case "CUSTOMER":
		// Crear la organización y vincularla al usuario admin en el mismo TransactWrite
		orgID = uuid.NewString()

		org := OrgItem{
			PK:          "ORGANIZATION#" + orgID,
			SK:          "#METADATA",
			ID:          orgID,
			OrgName:     req.OrgName,
			RutEmpresa:  req.OrgRut,
			Status:      "ACTIVE",
			AdminUserID: userID,
			CreatedAt:   now,
		}
		orgAV, err := attributevalue.MarshalMap(org)
		if err != nil {
			return writeResult{}, err
		}

		// Ítem principal de la organización
		transactItems = append(transactItems, types.TransactWriteItem{
			Put: &types.Put{
				TableName:           aws.String(h.tableName),
				Item:                orgAV,
				ConditionExpression: aws.String("attribute_not_exists(PK)"),
			},
		})

		// Link ORGANIZATION#<org_id>/USER#<user_id> — para consultar el admin de una org
		link := map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "ORGANIZATION#" + orgID},
			"SK": &types.AttributeValueMemberS{Value: "USER#" + userID},
		}
		transactItems = append(transactItems, types.TransactWriteItem{
			Put: &types.Put{
				TableName: aws.String(h.tableName),
				Item:      link,
			},
		})

		// Actualizar el usuario con su propio org_id (necesita un segundo put reemplazando el primero)
		user.OrgID = orgID
		userAV, err = attributevalue.MarshalMap(user)
		if err != nil {
			return writeResult{}, err
		}
		transactItems[0].Put.Item = userAV

	case "CUSTOMER_OPERATOR":
		// Link ORGANIZATION#<org_id>/OPERATOR#<user_id>
		if req.OrgID != "" {
			link := map[string]types.AttributeValue{
				"PK": &types.AttributeValueMemberS{Value: "ORGANIZATION#" + req.OrgID},
				"SK": &types.AttributeValueMemberS{Value: "OPERATOR#" + userID},
			}
			transactItems = append(transactItems, types.TransactWriteItem{
				Put: &types.Put{
					TableName: aws.String(h.tableName),
					Item:      link,
				},
			})
		}
	}

	_, err = h.dynamo.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{
		TransactItems: transactItems,
	})
	return writeResult{UserID: userID, OrgID: orgID}, err
}
