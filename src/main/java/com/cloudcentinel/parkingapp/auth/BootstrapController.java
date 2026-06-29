package com.cloudcentinel.parkingapp.auth;

import com.cloudcentinel.parkingapp.shared.cognito.CognitoAdminClient;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.rut.RutUtils;
import com.cloudcentinel.parkingapp.user.UserRepository;
import com.cloudcentinel.parkingapp.user.UserResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

@Profile("local")
@RestController
@RequestMapping("/auth")
public class BootstrapController {

    private final CognitoAdminClient cognito;
    private final UserRepository users;

    public BootstrapController(CognitoAdminClient cognito, UserRepository users) {
        this.cognito = cognito;
        this.users = users;
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<UserResponse> bootstrap(@RequestBody BootstrapRequest req) {
        String rut = RutUtils.normalizeAndValidate(req.rut());

        if (users.existsByRut(rut)) throw new ConflictException("rut_ya_registrado");
        if (users.existsByEmail(req.email())) throw new ConflictException("email_ya_registrado");

        String cognitoSub;
        try {
            cognitoSub = cognito.createUser(rut, req.password(), req.email(),
                    req.givenName(), req.familyName(), req.phoneNumber());
            cognito.addUserToGroup(rut, "ADMIN");
        } catch (UsernameExistsException e) {
            throw new ConflictException("rut_ya_registrado");
        }

        try {
            var created = users.insert(cognitoSub, rut, req.email(), req.givenName(),
                    req.familyName(), req.phoneNumber(), "ADMIN", null, null);
            return ResponseEntity.status(201).body(UserResponse.from(created));
        } catch (Exception e) {
            cognito.deleteUser(rut);
            throw e;
        }
    }
}
