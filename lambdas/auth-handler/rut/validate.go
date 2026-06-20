package rut

import (
	"strconv"
	"strings"
)

// Validate checks the module-11 check digit of a normalized RUT ("12345678-9").
// Must be called after Normalize.
func Validate(normalized string) bool {
	parts := strings.SplitN(normalized, "-", 2)
	if len(parts) != 2 {
		return false
	}

	body, dv := parts[0], parts[1]

	sum := 0
	mult := 2
	for i := len(body) - 1; i >= 0; i-- {
		digit, err := strconv.Atoi(string(body[i]))
		if err != nil {
			return false
		}
		sum += digit * mult
		mult++
		if mult > 7 {
			mult = 2
		}
	}

	remainder := 11 - (sum % 11)
	var expected string
	switch remainder {
	case 11:
		expected = "0"
	case 10:
		expected = "K"
	default:
		expected = strconv.Itoa(remainder)
	}

	return dv == expected
}
