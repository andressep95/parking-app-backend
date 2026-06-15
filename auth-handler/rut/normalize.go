package rut

import (
	"errors"
	"strings"
	"unicode"
)

// Normalize accepts "12.345.678-9", "12345678-9" or "123456789"
// and returns the canonical form "12345678-9".
func Normalize(raw string) (string, error) {
	s := strings.ToUpper(strings.TrimSpace(raw))
	s = strings.ReplaceAll(s, ".", "")
	s = strings.ReplaceAll(s, " ", "")

	if len(s) < 2 {
		return "", errors.New("rut demasiado corto")
	}

	var body, dv string

	if idx := strings.Index(s, "-"); idx != -1 {
		body = s[:idx]
		dv = s[idx+1:]
	} else {
		body = s[:len(s)-1]
		dv = string(s[len(s)-1])
	}

	if !isDigits(body) {
		return "", errors.New("cuerpo no numérico")
	}
	if dv != "K" && !isDigits(dv) {
		return "", errors.New("dígito verificador inválido")
	}
	if len(dv) != 1 {
		return "", errors.New("dígito verificador inválido")
	}

	return body + "-" + dv, nil
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if !unicode.IsDigit(r) {
			return false
		}
	}
	return true
}
