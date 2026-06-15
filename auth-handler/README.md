# auth-handler

Lambda en Go que maneja autenticación de usuarios contra Cognito.  
Corre en `provided.al2023` / `arm64`. El binario se llama `bootstrap`.

---

## Variables de entorno

| Variable | Descripción |
|---|---|
| `COGNITO_USER_POOL_ID` | ID del User Pool (inyectado por Terraform) |
| `COGNITO_CLIENT_ID` | ID del App Client (inyectado por Terraform) |
| `AWS_REGION` | Región AWS (disponible automáticamente en Lambda) |

---

## Endpoints

| Método | Ruta | Auth | Content-Type |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | Público | `application/json` |
| `POST` | `/api/v1/auth/register` | Público | `application/json` |
| `POST` | `/api/v1/auth/register/batch` | Público | `multipart/form-data` |

El router se resuelve con `event.RouteKey` del evento `APIGatewayV2HTTPRequest`.

---

## Structs Go

```go
// ── Login ──────────────────────────────────────────────────────────────────

type LoginRequest struct {
    RUT      string `json:"rut"`      // ej: "12345678-9"
    Password string `json:"password"`
}

type LoginResponse struct {
    AccessToken  string `json:"access_token"`
    IDToken      string `json:"id_token"`
    RefreshToken string `json:"refresh_token"`
    ExpiresIn    int32  `json:"expires_in"`
}

// ── Registro simple ────────────────────────────────────────────────────────

type RegisterRequest struct {
    RUT         string `json:"rut"`
    Password    string `json:"password"`
    Email       string `json:"email"`
    GivenName   string `json:"given_name"`
    FamilyName  string `json:"family_name"`
    PhoneNumber string `json:"phone_number,omitempty"` // opcional
}

// ── Registro batch ─────────────────────────────────────────────────────────
// El body es multipart/form-data con un campo "file" que contiene el Excel.
// La Lambda decodifica el archivo, lo parsea y procesa en chunks.

// Fila esperada en el Excel (la Lambda mapea columnas a este struct):
type ExcelRow struct {
    RUT         string // columna A
    Password    string // columna B
    Email       string // columna C
    GivenName   string // columna D
    FamilyName  string // columna E
    PhoneNumber string // columna F (opcional, puede estar vacía)
}

type BatchRegisterResponse struct {
    Total   int          `json:"total"`
    Created int          `json:"created"`
    Failed  int          `json:"failed"`
    Errors  []BatchError `json:"errors,omitempty"`
}

type BatchError struct {
    Row   int    `json:"row"`   // número de fila en el Excel (para que el admin ubique el error)
    RUT   string `json:"rut"`
    Error string `json:"error"`
}
```

---

## Planilla Excel esperada (batch)

La Lambda espera la primera hoja del archivo con la siguiente estructura.  
La **fila 1 es el header** y se ignora. Los datos empiezan en la fila 2.

| A | B | C | D | E | F |
|---|---|---|---|---|---|
| rut | password | email | nombre | apellido | telefono |
| 12345678-9 | Pass123! | juan@mail.com | Juan | Pérez | +56912345678 |
| 87654321-K | Pass456! | maria@mail.com | María | López | |

---

## Lógica de RUT (a implementar manualmente)

El RUT chileno tiene formato `XXXXXXXX-D` donde `D` es el dígito verificador (0-9 o K).

### Normalización (antes de cualquier llamada a Cognito)

```go
// Entradas aceptadas: "12.345.678-9" | "12345678-9" | "123456789"
// Salida esperada:    "12345678-9"
func NormalizeRUT(raw string) (string, error) {
    // 1. Remover puntos y espacios
    // 2. Pasar a mayúsculas (K)
    // 3. Separar cuerpo y dígito verificador (último caracter)
    // 4. Retornar en formato "CUERPO-DIGITO"
}
```

### Validación del dígito verificador (módulo 11)

```go
func ValidateRUT(rut string) bool {
    // 1. Separar cuerpo numérico y dígito verificador
    // 2. Multiplicar cada dígito del cuerpo (de derecha a izquierda)
    //    por la secuencia: 2, 3, 4, 5, 6, 7, 2, 3, 4...
    // 3. Sumar los productos
    // 4. Calcular: 11 - (suma % 11)
    //    - Si resultado == 11 → dígito es "0"
    //    - Si resultado == 10 → dígito es "K"
    //    - Si no             → dígito es el resultado como string
    // 5. Comparar con el dígito verificador recibido
}
```

---

## Flujo: Login

```mermaid
sequenceDiagram
    participant C as Cliente
    participant APIGW as API Gateway
    participant L as Lambda auth-handler
    participant COG as Cognito

    C->>APIGW: POST /api/v1/auth/login<br/>{rut, password}
    APIGW->>L: APIGatewayV2HTTPRequest

    L->>L: NormalizeRUT(rut)
    L->>L: ValidateRUT(rut)
    alt RUT inválido
        L-->>C: 400 {error: "rut_invalido"}
    end

    L->>COG: InitiateAuth<br/>AuthFlow: USER_PASSWORD_AUTH<br/>USERNAME: rut_normalizado<br/>PASSWORD: password
    alt Credenciales incorrectas
        COG-->>L: NotAuthorizedException
        L-->>C: 401 {error: "credenciales_invalidas"}
    end
    alt Usuario no existe
        COG-->>L: UserNotFoundException
        L-->>C: 404 {error: "usuario_no_encontrado"}
    end

    COG-->>L: AuthenticationResult
    L-->>C: 200 {access_token, id_token, refresh_token, expires_in}
```

---

## Flujo: Registro simple (1 usuario)

```mermaid
sequenceDiagram
    participant C as Cliente
    participant APIGW as API Gateway
    participant L as Lambda auth-handler
    participant COG as Cognito

    C->>APIGW: POST /api/v1/auth/register<br/>{rut, password, email, given_name, family_name, phone_number?}
    APIGW->>L: APIGatewayV2HTTPRequest

    L->>L: NormalizeRUT + ValidateRUT
    alt RUT inválido
        L-->>C: 400 {error: "rut_invalido"}
    end
    L->>L: Validar campos requeridos<br/>(email, given_name, family_name)

    L->>COG: AdminCreateUser<br/>Username: rut_normalizado<br/>UserAttributes: [email, given_name, family_name, phone_number?]<br/>MessageAction: SUPPRESS
    alt Usuario ya existe
        COG-->>L: UsernameExistsException
        L-->>C: 409 {error: "usuario_ya_existe"}
    end

    L->>COG: AdminSetUserPassword<br/>Username: rut_normalizado<br/>Password: password<br/>Permanent: true

    L-->>C: 201 {message: "usuario_creado"}
```

---

## Flujo: Registro batch (Excel → Lambda)

El archivo Excel llega directamente a la Lambda como `multipart/form-data`.  
API Gateway HTTP v2 entrega el body en **base64** (`event.IsBase64Encoded = true`).  
La Lambda procesa los usuarios en **chunks** para no saturar Cognito ni agotar el timeout.

```mermaid
sequenceDiagram
    participant U as Usuario Admin
    participant FE as Frontend
    participant APIGW as API Gateway
    participant L as Lambda auth-handler
    participant COG as Cognito

    U->>FE: Selecciona archivo Excel
    FE->>APIGW: POST /api/v1/auth/register/batch<br/>Content-Type: multipart/form-data<br/>campo "file": archivo.xlsx
    APIGW->>L: event.Body (base64)<br/>event.IsBase64Encoded = true

    L->>L: base64.Decode(event.Body)
    L->>L: Parsear multipart → extraer campo "file"
    L->>L: excelize.OpenReader(fileBytes)<br/>Leer Sheet1 desde fila 2

    L->>L: Mapear filas → []ExcelRow<br/>Validar que no esté vacío

    loop Por cada chunk de 25 usuarios
        loop Por cada usuario en el chunk
            L->>L: NormalizeRUT + ValidateRUT
            alt RUT inválido
                L->>L: failed++ / errors.append({row, rut, error})
            else RUT válido
                L->>COG: AdminCreateUser(rut, atributos, SUPPRESS)
                alt Error Cognito (duplicado u otro)
                    L->>L: failed++ / errors.append({row, rut, error})
                else OK
                    L->>COG: AdminSetUserPassword(rut, password, Permanent=true)
                    alt Error al setear password
                        L->>L: failed++ / errors.append({row, rut, error})
                    else OK
                        L->>L: created++
                    end
                end
            end
        end
    end

    L-->>FE: 200 {total, created, failed, errors:[{row, rut, error}]}
    FE-->>U: Resumen: X creados, Y fallidos<br/>(con número de fila para ubicar errores)
```

---

## Consideraciones de escala para batch

| Escenario | Recomendación |
|---|---|
| < 500 usuarios | Lambda directa, chunks de 25, timeout 30s es suficiente |
| 500 - 2000 usuarios | Aumentar timeout a 5 min en Terraform (`timeout = 300`) |
| > 2000 usuarios | Subir Excel a S3 → S3 Event → Lambda asíncrona (siguiente fase) |

El tamaño de chunk de 25 está pensado para no superar el rate limit de Cognito (`AdminCreateUser` tiene un límite por defecto de 50 req/s).

---

## Estructura de archivos sugerida (Go)

```
auth-handler/
├── README.md
├── main.go              ← entry point, inicializa cliente Cognito
├── handler.go           ← router por RouteKey
├── login.go             ← InitiateAuth
├── register.go          ← AdminCreateUser + AdminSetUserPassword (un usuario)
├── register_batch.go    ← parsea Excel, chunking, llama register.go por usuario
└── rut/
    ├── normalize.go     ← limpia y formatea el RUT
    └── validate.go      ← módulo 11
```

**Dependencia Go necesaria:**
```bash
go get github.com/xuri/excelize/v2   # parseo de .xlsx
```

---

## Llamadas Cognito SDK (Go)

```go
// Login
cognitoClient.InitiateAuth(ctx, &cognitoidentityprovider.InitiateAuthInput{
    AuthFlow: types.AuthFlowTypeUserPasswordAuth,
    ClientId: aws.String(os.Getenv("COGNITO_CLIENT_ID")),
    AuthParameters: map[string]string{
        "USERNAME": rutNormalizado,
        "PASSWORD": password,
    },
})

// Register - paso 1: crear usuario sin enviar email
cognitoClient.AdminCreateUser(ctx, &cognitoidentityprovider.AdminCreateUserInput{
    UserPoolId:    aws.String(os.Getenv("COGNITO_USER_POOL_ID")),
    Username:      aws.String(rutNormalizado),
    MessageAction: types.MessageActionTypeSuppress,
    UserAttributes: []types.AttributeType{
        {Name: aws.String("email"),        Value: aws.String(email)},
        {Name: aws.String("given_name"),   Value: aws.String(givenName)},
        {Name: aws.String("family_name"),  Value: aws.String(familyName)},
        {Name: aws.String("phone_number"), Value: aws.String(phone)}, // solo si viene
    },
})

// Register - paso 2: setear password permanente (sin obligar cambio)
cognitoClient.AdminSetUserPassword(ctx, &cognitoidentityprovider.AdminSetUserPasswordInput{
    UserPoolId: aws.String(os.Getenv("COGNITO_USER_POOL_ID")),
    Username:   aws.String(rutNormalizado),
    Password:   aws.String(password),
    Permanent:  true,
})

// Batch: parseo del Excel
import "github.com/xuri/excelize/v2"

f, err := excelize.OpenReader(bytes.NewReader(fileBytes))
rows, err := f.GetRows("Sheet1")
for i, row := range rows {
    if i == 0 { continue } // saltar header
    // row[0]=RUT, row[1]=Password, row[2]=Email, row[3]=GivenName, row[4]=FamilyName, row[5]=Phone
}
```
