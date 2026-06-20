package main

import (
	"context"
	"strconv"
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

// ─── Sesiones ─────────────────────────────────────────────────────────────────

// writeSessionActive escribe USER#<id>/SESSION#ACTIVE con TTL de 24h.
func (h *Handler) writeSessionActive(ctx context.Context, userID, terminalID string) error {
	now := time.Now().UTC()
	item := map[string]types.AttributeValue{
		"PK":         &types.AttributeValueMemberS{Value: "USER#" + userID},
		"SK":         &types.AttributeValueMemberS{Value: "SESSION#ACTIVE"},
		"session_id": &types.AttributeValueMemberS{Value: uuid.NewString()},
		"started_at": &types.AttributeValueMemberS{Value: now.Format(time.RFC3339)},
		"ttl":        &types.AttributeValueMemberN{Value: strconv.FormatInt(now.Add(24*time.Hour).Unix(), 10)},
	}
	if terminalID != "" {
		item["terminal_id"] = &types.AttributeValueMemberS{Value: terminalID}
	}
	_, err := h.dynamo.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(h.tableName),
		Item:      item,
	})
	return err
}

// deleteSessionActive elimina USER#<id>/SESSION#ACTIVE.
func (h *Handler) deleteSessionActive(ctx context.Context, userID string) error {
	_, err := h.dynamo.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "USER#" + userID},
			"SK": &types.AttributeValueMemberS{Value: "SESSION#ACTIVE"},
		},
	})
	return err
}

// userIDBySub busca el user_id interno via GSI1 usando el cognito_sub.
func (h *Handler) userIDBySub(ctx context.Context, sub string) (string, error) {
	out, err := h.dynamo.Query(ctx, &dynamodb.QueryInput{
		TableName:              aws.String(h.tableName),
		IndexName:              aws.String("GSI1"),
		KeyConditionExpression: aws.String("GSI1PK = :pk"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":pk": &types.AttributeValueMemberS{Value: "COGNITO#" + sub},
		},
		Limit:                aws.Int32(1),
		ProjectionExpression: aws.String("id"),
	})
	if err != nil || len(out.Items) == 0 {
		return "", err
	}
	if v, ok := out.Items[0]["id"].(*types.AttributeValueMemberS); ok {
		return v.Value, nil
	}
	return "", nil
}

// userRUTByID obtiene el RUT (username Cognito) del usuario por su UUID interno.
func (h *Handler) userRUTByID(ctx context.Context, userID string) (string, error) {
	out, err := h.dynamo.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: aws.String(h.tableName),
		Key: map[string]types.AttributeValue{
			"PK": &types.AttributeValueMemberS{Value: "USER#" + userID},
			"SK": &types.AttributeValueMemberS{Value: "#METADATA"},
		},
		ProjectionExpression: aws.String("rut"),
	})
	if err != nil || out.Item == nil {
		return "", err
	}
	if v, ok := out.Item["rut"].(*types.AttributeValueMemberS); ok {
		return v.Value, nil
	}
	return "", nil
}
