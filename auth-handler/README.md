# auth-handler

Lambda en Go que maneja autenticación de usuarios contra Cognito.  
Corre en `provided.al2023` / `arm64`. El binario se llama `bootstrap`.

---

## Arquitectura del componente

```mermaid
graph LR
    subgraph Cliente
        WEB([Web / App])
        ANDROID([Terminal Android])
    end

    subgraph AWS
        APIGW[API Gateway\nHTTP v2]
        L["⚡ auth-handler\n(Lambda Go arm64)"]
        COG[(Cognito\nUser Pool)]
    end

    WEB -->|POST /login\nPOST /register| APIGW
    ANDROID -->|POST /login| APIGW
    APIGW -->|APIGatewayV2HTTPRequest| L
    L -->|InitiateAuth| COG
    L -->|AdminCreateUser\nAdminSetUserPassword| COG
    COG -->|tokens / errores| L
    L -->|JSON response| APIGW
    APIGW -->|HTTP response| WEB
    APIGW -->|HTTP response| ANDROID
```

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

type ExcelRow struct {
    RUT         string // columna A
    Password    string // columna B
    Email       string // columna C
    GivenName   string // columna D
    FamilyName  string // columna E
    PhoneNumber string // columna F (opcional)
}

type BatchRegisterResponse struct {
    Total   int          `json:"total"`
    Created int          `json:"created"`
    Failed  int          `json:"failed"`
    Errors  []BatchError `json:"errors,omitempty"`
}

type BatchError struct {
    Row   int    `json:"row"`   // fila en el Excel para que el admin ubique el error
    RUT   string `json:"rut"`
    Error string `json:"error"`
}
```

---

## Lógica de RUT (a implementar manualmente)

El RUT chileno tiene formato `XXXXXXXX-D` donde `D` es el dígito verificador (0-9 o K).

```go
// Entradas aceptadas: "12.345.678-9" | "12345678-9" | "123456789"
// Salida esperada:    "12345678-9"
func NormalizeRUT(raw string) (string, error)

// Algoritmo módulo 11:
// 1. Separar cuerpo numérico y dígito verificador
// 2. Multiplicar cada dígito del cuerpo (derecha → izquierda) por secuencia 2,3,4,5,6,7,2,3,4...
// 3. Sumar productos → resultado = 11 - (suma % 11)
//    - resultado == 11 → dígito "0"
//    - resultado == 10 → dígito "K"
//    - otro           → dígito = resultado como string
// 4. Comparar con el dígito verificador recibido
func ValidateRUT(rut string) bool
```

---

## Flujo 1 — Login

### Diagrama de secuencia

```mermaid
sequenceDiagram
    actor U as Usuario
    participant FE as Cliente
    participant GW as API Gateway
    participant L as auth-handler
    participant C as Cognito

    U->>FE: Ingresa RUT + Password
    FE->>GW: POST /api/v1/auth/login
    GW->>L: APIGatewayV2HTTPRequest

    L->>L: Parsear body JSON
    L->>L: NormalizeRUT()
    L->>L: ValidateRUT()

    alt RUT inválido
        L-->>FE: 400 { error: "rut_invalido" }
        FE-->>U: ❌ RUT inválido
    else RUT válido
        L->>C: InitiateAuth\nUSER_PASSWORD_AUTH\nUSERNAME=rut, PASSWORD=password

        alt Usuario no existe
            C-->>L: UserNotFoundException
            L-->>FE: 404 { error: "usuario_no_encontrado" }
            FE-->>U: ❌ Usuario no existe
        else Credenciales incorrectas
            C-->>L: NotAuthorizedException
            L-->>FE: 401 { error: "credenciales_invalidas" }
            FE-->>U: ❌ RUT o contraseña incorrectos
        else Autenticación exitosa
            C-->>L: AuthenticationResult\n{ AccessToken, IDToken, RefreshToken }
            L-->>FE: 200 { access_token, id_token, refresh_token, expires_in }
            FE-->>U: ✅ Sesión iniciada
        end
    end
```

### Diagrama de flujo interno (Lambda)

```mermaid
flowchart TD
    A([Inicio]) --> B[Parsear body JSON\n→ LoginRequest]
    B --> C{¿Parseo OK?}
    C -- No --> E1[return 400\nbody inválido]
    C -- Sí --> D[NormalizeRUT]
    D --> F[ValidateRUT\nmódulo 11]
    F --> G{¿RUT válido?}
    G -- No --> E2[return 400\nrut_invalido]
    G -- Sí --> H[InitiateAuth\nCognito SDK]
    H --> I{¿Respuesta\nCognito?}
    I -- UserNotFoundException --> E3[return 404\nusuario_no_encontrado]
    I -- NotAuthorizedException --> E4[return 401\ncredenciales_invalidas]
    I -- Error genérico --> E5[return 500\nerror_interno]
    I -- AuthenticationResult --> J[Armar LoginResponse]
    J --> K([return 200\naccess_token + tokens])
```

---

## Flujo 2 — Registro simple (1 usuario)

### Diagrama de secuencia

```mermaid
sequenceDiagram
    actor A as Admin
    participant FE as Cliente
    participant GW as API Gateway
    participant L as auth-handler
    participant C as Cognito

    A->>FE: Completa formulario de registro
    FE->>GW: POST /api/v1/auth/register\n{ rut, password, email, given_name, family_name, phone? }
    GW->>L: APIGatewayV2HTTPRequest

    L->>L: Parsear body JSON
    L->>L: NormalizeRUT()
    L->>L: ValidateRUT()

    alt RUT inválido
        L-->>FE: 400 { error: "rut_invalido" }
        FE-->>A: ❌ RUT inválido
    else RUT válido
        L->>L: Validar campos requeridos\n(email, given_name, family_name)

        alt Campos faltantes
            L-->>FE: 400 { error: "campos_requeridos" }
        else Datos completos
            L->>C: AdminCreateUser\nUsername=rut, MessageAction=SUPPRESS\nUserAttributes=[email, given_name, family_name, phone?]

            alt Usuario ya existe
                C-->>L: UsernameExistsException
                L-->>FE: 409 { error: "usuario_ya_existe" }
                FE-->>A: ❌ El RUT ya está registrado
            else Usuario creado
                C-->>L: 200 OK
                L->>C: AdminSetUserPassword\nUsername=rut, Password=password, Permanent=true
                alt Error al setear contraseña
                    C-->>L: Error
                    L-->>FE: 500 { error: "error_al_setear_password" }
                else Contraseña seteada
                    C-->>L: 200 OK
                    L-->>FE: 201 { message: "usuario_creado" }
                    FE-->>A: ✅ Usuario registrado
                end
            end
        end
    end
```

### Diagrama de flujo interno (Lambda)

```mermaid
flowchart TD
    A([Inicio]) --> B[Parsear body JSON\n→ RegisterRequest]
    B --> C{¿Parseo OK?}
    C -- No --> E1[return 400\nbody inválido]
    C -- Sí --> D[NormalizeRUT]
    D --> F[ValidateRUT\nmódulo 11]
    F --> G{¿RUT válido?}
    G -- No --> E2[return 400\nrut_invalido]
    G -- Sí --> H{¿Campos\nrequeridos OK?}
    H -- No --> E3[return 400\ncampos_requeridos]
    H -- Sí --> I[AdminCreateUser\nMessageAction: SUPPRESS]
    I --> J{¿Respuesta\nCognito?}
    J -- UsernameExistsException --> E4[return 409\nusuario_ya_existe]
    J -- Error genérico --> E5[return 500]
    J -- OK --> K[AdminSetUserPassword\nPermanent: true]
    K --> L2{¿OK?}
    L2 -- Error --> E6[return 500\nerror_al_setear_password]
    L2 -- OK --> M([return 201\nusuario_creado])
```

---

## Flujo 3 — Registro batch (Excel → Lambda)

El archivo llega directamente a la Lambda como `multipart/form-data`.  
API Gateway HTTP v2 entrega el body en **base64** (`event.IsBase64Encoded = true`).  
La Lambda procesa en **chunks de 25** para respetar el rate limit de Cognito.

### Planilla Excel esperada

La Lambda lee la primera hoja. **Fila 1 = header, se ignora. Datos desde fila 2.**

| A | B | C | D | E | F |
|---|---|---|---|---|---|
| rut | password | email | nombre | apellido | telefono |
| 12345678-9 | Pass123! | juan@mail.com | Juan | Pérez | +56912345678 |
| 87654321-K | Pass456! | maria@mail.com | María | López | *(vacío)* |

### Diagrama de secuencia

```mermaid
sequenceDiagram
    actor A as Admin
    participant FE as Frontend
    participant GW as API Gateway
    participant L as auth-handler
    participant C as Cognito

    A->>FE: Sube archivo Excel (.xlsx)
    FE->>GW: POST /api/v1/auth/register/batch\nContent-Type: multipart/form-data\ncampo "file": archivo.xlsx
    GW->>L: event.Body (base64)\nevent.IsBase64Encoded = true

    L->>L: base64.Decode(event.Body)
    L->>L: Parsear multipart → extraer campo "file"
    L->>L: excelize.OpenReader(fileBytes)
    L->>L: Leer Sheet1 desde fila 2\n→ []ExcelRow

    note over L: Divide filas en chunks de 25

    loop chunk 1..N (cada 25 usuarios)
        loop usuario en chunk
            L->>L: NormalizeRUT + ValidateRUT
            alt RUT inválido
                L->>L: failed++ · errors.append\n{row, rut, "rut_invalido"}
            else RUT válido
                L->>C: AdminCreateUser\n(rut, atributos, SUPPRESS)
                alt UsernameExistsException u otro error
                    C-->>L: Error
                    L->>L: failed++ · errors.append\n{row, rut, error_message}
                else OK
                    L->>C: AdminSetUserPassword\n(rut, password, Permanent=true)
                    alt Error
                        C-->>L: Error
                        L->>L: failed++ · errors.append\n{row, rut, error_message}
                    else OK
                        L->>L: created++
                    end
                end
            end
        end
    end

    L-->>FE: 200 { total, created, failed, errors:[{row,rut,error}] }
    FE-->>A: ✅ X creados · ❌ Y fallidos\n(con número de fila para corregir)
```

### Diagrama de flujo interno (Lambda)

```mermaid
flowchart TD
    A([Inicio]) --> B[base64.Decode\nevent.Body]
    B --> B2[Parsear multipart\nextraer campo 'file']
    B2 --> C{¿Archivo\npresente?}
    C -- No --> E1[return 400\narchivo_requerido]
    C -- Sí --> D[excelize.OpenReader\nleer Sheet1]
    D --> D2{¿Lectura\nOK?}
    D2 -- No --> E2[return 400\narchivo_invalido]
    D2 -- Sí --> D3[Filtrar filas\nsaltar header fila 1]
    D3 --> D4{¿Hay filas\ncon datos?}
    D4 -- No --> E3[return 400\nplanilla_vacia]
    D4 -- Sí --> E[Dividir en\nchunks de 25]
    E --> F{¿Quedan\nchunks?}
    F -- No --> Z([return 200\ntotal · created · failed · errors])
    F -- Sí --> G[Tomar siguiente\nchunk]
    G --> H{¿Quedan usuarios\nen el chunk?}
    H -- No --> F
    H -- Sí --> I[NormalizeRUT\nValidateRUT]
    I --> J{¿RUT\nválido?}
    J -- No --> K1[failed++\nerrors.append\nrow·rut·rut_invalido]
    K1 --> H
    J -- Sí --> L2[AdminCreateUser\nSUPPRESS]
    L2 --> M{¿OK?}
    M -- Error\nCognito --> K2[failed++\nerrors.append\nrow·rut·error]
    K2 --> H
    M -- OK --> N[AdminSetUserPassword\nPermanent=true]
    N --> O{¿OK?}
    O -- Error --> K3[failed++\nerrors.append\nrow·rut·error]
    K3 --> H
    O -- OK --> P[created++]
    P --> H
```

---

## Consideraciones de escala para batch

| Escenario | Recomendación |
|---|---|
| < 500 usuarios | Lambda directa, chunks de 25, timeout 30s es suficiente |
| 500 – 2000 usuarios | Aumentar timeout a 5 min en Terraform (`timeout = 300`) |
| > 2000 usuarios | Subir Excel a S3 → S3 Event → Lambda asíncrona (siguiente fase) |

> El chunk de 25 respeta el rate limit de Cognito (`AdminCreateUser` tiene límite de 50 req/s por defecto).

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

// Register paso 1 — crear usuario sin enviar email de confirmación
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

// Register paso 2 — setear contraseña permanente (sin forzar cambio)
cognitoClient.AdminSetUserPassword(ctx, &cognitoidentityprovider.AdminSetUserPasswordInput{
    UserPoolId: aws.String(os.Getenv("COGNITO_USER_POOL_ID")),
    Username:   aws.String(rutNormalizado),
    Password:   aws.String(password),
    Permanent:  true,
})

// Batch — parseo del Excel con excelize
f, err := excelize.OpenReader(bytes.NewReader(fileBytes))
rows, err := f.GetRows("Sheet1")
for i, row := range rows {
    if i == 0 { continue } // saltar header
    // row[0]=RUT, row[1]=Password, row[2]=Email,
    // row[3]=GivenName, row[4]=FamilyName, row[5]=Phone
}
```

**Dependencia Go:**
```bash
go get github.com/xuri/excelize/v2
```

---

## Estructura de archivos sugerida (Go)

```
auth-handler/
├── README.md
├── go.mod
├── go.sum
├── main.go              ← entry point, inicializa cliente Cognito, llama handler.Route()
├── handler.go           ← router por event.RouteKey
├── login.go             ← HandleLogin → InitiateAuth
├── register.go          ← HandleRegister → AdminCreateUser + AdminSetUserPassword
├── register_batch.go    ← HandleBatch → parsea Excel, chunking, llama register por usuario
└── rut/
    ├── normalize.go     ← NormalizeRUT: limpia puntos, espacios, agrega guión
    └── validate.go      ← ValidateRUT: módulo 11
```
