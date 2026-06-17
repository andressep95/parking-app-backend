package main

// OrgItem mirrors the DynamoDB ORGANIZATION#<id>/#METADATA item.
// Attribute names avoid DynamoDB reserved words: name→org_name, status→org_status.
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

// OrgResponse is the public API representation.
type OrgResponse struct {
	ID          string `json:"id"`
	OrgName     string `json:"org_name"`
	RutEmpresa  string `json:"rut_empresa,omitempty"`
	Email       string `json:"email,omitempty"`
	Phone       string `json:"phone_number,omitempty"`
	Status      string `json:"status"`
	AdminUserID string `json:"admin_user_id"`
	CreatedAt   string `json:"created_at"`
}

func toOrgResponse(o OrgItem) OrgResponse {
	return OrgResponse{
		ID:          o.ID,
		OrgName:     o.OrgName,
		RutEmpresa:  o.RutEmpresa,
		Email:       o.Email,
		Phone:       o.Phone,
		Status:      o.Status,
		AdminUserID: o.AdminUserID,
		CreatedAt:   o.CreatedAt,
	}
}
