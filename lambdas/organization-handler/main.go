package main

import (
	"context"
	"os"

	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

func main() {
	cfg, err := config.LoadDefaultConfig(context.Background())
	if err != nil {
		panic("error cargando config AWS: " + err.Error())
	}

	h := &Handler{
		dynamo:    dynamodb.NewFromConfig(cfg),
		tableName: os.Getenv("DYNAMODB_TABLE_NAME"),
	}

	lambda.Start(h.Route)
}
