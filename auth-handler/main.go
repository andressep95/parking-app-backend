package main

import (
	"context"
	"os"

	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
)

func main() {
	cfg, err := config.LoadDefaultConfig(context.Background())
	if err != nil {
		panic("error cargando config AWS: " + err.Error())
	}

	h := &Handler{
		cognito:    cognitoidentityprovider.NewFromConfig(cfg),
		userPoolID: os.Getenv("COGNITO_USER_POOL_ID"),
		clientID:   os.Getenv("COGNITO_CLIENT_ID"),
	}

	lambda.Start(h.Route)
}
