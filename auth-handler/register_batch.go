package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"io"
	"mime"
	"mime/multipart"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/andressep95/auth-handler/rut"
	"github.com/xuri/excelize/v2"
)

type BatchRegisterResponse struct {
	Total   int          `json:"total"`
	Created int          `json:"created"`
	Failed  int          `json:"failed"`
	Errors  []BatchError `json:"errors,omitempty"`
}

type BatchError struct {
	Row   int    `json:"row"`
	RUT   string `json:"rut"`
	Error string `json:"error"`
}

func (h *Handler) HandleBatch(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	bodyBytes, err := decodeBody(event)
	if err != nil {
		return jsonResponse(400, map[string]string{"error": "body_invalido"}), nil
	}

	fileBytes, err := extractFile(event, bodyBytes)
	if err != nil {
		return jsonResponse(400, map[string]string{"error": "archivo_requerido"}), nil
	}

	f, err := excelize.OpenReader(bytes.NewReader(fileBytes))
	if err != nil {
		return jsonResponse(400, map[string]string{"error": "archivo_invalido"}), nil
	}

	rows, err := f.GetRows("Sheet1")
	if err != nil || len(rows) <= 1 {
		return jsonResponse(400, map[string]string{"error": "planilla_vacia"}), nil
	}

	data := rows[1:] // fila 1 es header
	result := BatchRegisterResponse{Total: len(data)}

	const chunkSize = 25
	for i := 0; i < len(data); i += chunkSize {
		end := i + chunkSize
		if end > len(data) {
			end = len(data)
		}
		for j, row := range data[i:end] {
			rowNum := i + j + 2 // +2: índice 0 + header eliminado

			if len(row) < 5 {
				result.Failed++
				result.Errors = append(result.Errors, BatchError{Row: rowNum, RUT: safeGet(row, 0), Error: "fila_incompleta"})
				continue
			}

			normalized, err := rut.Normalize(row[0])
			if err != nil || !rut.Validate(normalized) {
				result.Failed++
				result.Errors = append(result.Errors, BatchError{Row: rowNum, RUT: row[0], Error: "rut_invalido"})
				continue
			}

			// Excel columns: RUT, GivenName, FamilyName, Email, Password, Phone(optional)
			sub, createErr := h.createCognitoUser(ctx, normalized, row[4], row[3], row[1], row[2], safeGet(row, 5))
			if createErr != nil {
				result.Failed++
				result.Errors = append(result.Errors, BatchError{Row: rowNum, RUT: normalized, Error: "error_al_crear_usuario"})
				continue
			}

			batchReq := RegisterRequest{
				RUT:         normalized,
				Email:       row[3],
				GivenName:   row[1],
				FamilyName:  row[2],
				PhoneNumber: safeGet(row, 5),
			}
			if _, writeErr := h.writeUserRecord(ctx, sub, normalized, batchReq, "CUSTOMER_OPERATOR"); writeErr != nil {
				result.Failed++
				result.Errors = append(result.Errors, BatchError{Row: rowNum, RUT: normalized, Error: "error_dynamo"})
				continue
			}
			result.Created++
		}
	}

	return jsonResponse(200, result), nil
}

func decodeBody(event events.APIGatewayV2HTTPRequest) ([]byte, error) {
	if event.IsBase64Encoded {
		return base64.StdEncoding.DecodeString(event.Body)
	}
	return []byte(event.Body), nil
}

func extractFile(event events.APIGatewayV2HTTPRequest, body []byte) ([]byte, error) {
	ct := event.Headers["content-type"]
	if ct == "" {
		ct = event.Headers["Content-Type"]
	}

	mediaType, params, err := mime.ParseMediaType(ct)
	if err != nil || !strings.HasPrefix(mediaType, "multipart/") {
		return nil, errors.New("content-type no es multipart")
	}

	mr := multipart.NewReader(bytes.NewReader(body), params["boundary"])
	for {
		part, err := mr.NextPart()
		if err != nil {
			break
		}
		if part.FormName() == "file" {
			return io.ReadAll(part)
		}
	}
	return nil, errors.New("campo 'file' no encontrado")
}

func safeGet(row []string, i int) string {
	if i < len(row) {
		return row[i]
	}
	return ""
}
