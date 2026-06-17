package main

// UserItem mirrors the DynamoDB item structure.
// Attribute names avoid DynamoDB reserved words: email→email_addr, status→user_status.
type UserItem struct {
	PK         string `dynamodbav:"PK"`
	SK         string `dynamodbav:"SK"`
	GSI1PK     string `dynamodbav:"GSI1PK,omitempty"`
	GSI1SK     string `dynamodbav:"GSI1SK,omitempty"`
	GSI2PK     string `dynamodbav:"GSI2PK,omitempty"`
	GSI2SK     string `dynamodbav:"GSI2SK,omitempty"`
	ID         string `dynamodbav:"id"`
	RUT        string `dynamodbav:"rut"`
	Email      string `dynamodbav:"email_addr"`
	GivenName  string `dynamodbav:"given_name"`
	FamilyName string `dynamodbav:"family_name"`
	Phone      string `dynamodbav:"phone_number,omitempty"`
	Role       string `dynamodbav:"role"`
	Status     string `dynamodbav:"user_status"`
	CustomerID string `dynamodbav:"customer_id,omitempty"`
	LocationID string `dynamodbav:"location_id,omitempty"`
	CognitoSub string `dynamodbav:"cognito_sub"`
	CreatedAt  string `dynamodbav:"created_at"`
}

// UserResponse is the public API representation (no DynamoDB internals).
type UserResponse struct {
	ID         string `json:"id"`
	RUT        string `json:"rut"`
	Email      string `json:"email"`
	GivenName  string `json:"given_name"`
	FamilyName string `json:"family_name"`
	Phone      string `json:"phone_number,omitempty"`
	Role       string `json:"role"`
	Status     string `json:"status"`
	CustomerID string `json:"customer_id,omitempty"`
	LocationID string `json:"location_id,omitempty"`
	CreatedAt  string `json:"created_at"`
}

func toResponse(u UserItem) UserResponse {
	return UserResponse{
		ID:         u.ID,
		RUT:        u.RUT,
		Email:      u.Email,
		GivenName:  u.GivenName,
		FamilyName: u.FamilyName,
		Phone:      u.Phone,
		Role:       u.Role,
		Status:     u.Status,
		CustomerID: u.CustomerID,
		LocationID: u.LocationID,
		CreatedAt:  u.CreatedAt,
	}
}
