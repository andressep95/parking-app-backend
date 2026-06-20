package com.cloudcentinel.parkingapp.auth;

/**
 * Cuerpo de la petición {@code POST /auth/device}.
 *
 * <p>Este endpoint es exclusivo para terminales POS Android con rol
 * {@code OPERATOR}. Los usuarios {@code ADMIN} y {@code CUSTOMER} se
 * autentican directamente contra Cognito desde el dashboard web y deben
 * ser rechazados si llegan aquí.</p>
 *
 * <h2>Reglas de validación por rol</h2>
 * <ul>
 *   <li><b>OPERATOR</b>: {@code serialNumber} es obligatorio. El backend
 *       valida que el terminal exista y esté {@code ONLINE} antes de llamar
 *       a Cognito. Adicionalmente, el operador no puede estar ya logueado:
 *       si tiene una sesión activa en cualquier terminal, el login es
 *       rechazado.</li>
 *   <li><b>ADMIN / CUSTOMER</b>: si llegan a este endpoint con o sin
 *       {@code serialNumber}, son rechazados inmediatamente. Su canal de
 *       autenticación es el dashboard web (Cognito directo).</li>
 * </ul>
 *
 * @param rut          RUT del operador en cualquier formato válido.
 *                     El service lo normaliza a {@code "XXXXXXXX-D"} y
 *                     valida el dígito verificador antes de usarlo como
 *                     username en Cognito.
 * @param password     Contraseña en texto plano. Solo viaja por TLS entre
 *                     el POS y el backend, y entre el backend y Cognito.
 *                     El backend nunca la persiste.
 * @param serialNumber Número de serie físico del terminal TUU. Obligatorio
 *                     para OPERATOR, rechazado para otros roles. Puede venir
 *                     {@code null} o vacío desde el cliente; el service lo
 *                     valida según el rol del usuario.
 */
public record DeviceLoginRequest(
        String rut,
        String password,
        String serialNumber
) {}
