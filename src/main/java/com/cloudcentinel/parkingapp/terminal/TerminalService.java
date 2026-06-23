package com.cloudcentinel.parkingapp.terminal;

import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TerminalService {

    private final TerminalRepository terminals;
    private final UserRepository     users;

    public TerminalService(TerminalRepository terminals, UserRepository users) {
        this.terminals = terminals;
        this.users     = users;
    }

    public Terminal registerTerminal(CreateTerminalRequest req) {
        if (req.serialNumber() == null || req.model() == null || req.orgId() == null) {
            throw new BadRequestException("campos_requeridos_faltantes");
        }
        if (terminals.existsBySerialNumber(req.serialNumber())) {
            throw new ConflictException("serial_number_ya_registrado");
        }
        return terminals.insert(req.serialNumber(), req.model(), req.orgId(), req.appVersion());
    }

    public List<Terminal> listTerminals(Jwt jwt, UUID orgId) {
        if (isAdmin(jwt)) {
            return orgId != null ? terminals.findByOrgId(orgId) : terminals.findAll();
        }
        UUID callerOrgId = loadCaller(jwt).orgId();
        if (orgId != null && !orgId.equals(callerOrgId)) throw new ForbiddenException("sin_permiso");
        return terminals.findByOrgId(callerOrgId);
    }

    public Terminal getTerminal(Jwt jwt, UUID id) {
        Terminal t = terminals.findById(id)
                .orElseThrow(() -> new NotFoundException("terminal_no_encontrado"));
        assertAccess(jwt, t.orgId());
        return t;
    }

    public Terminal updateTerminal(UUID id, UpdateTerminalRequest req) {
        if (!req.hasAnyField()) throw new BadRequestException("sin_campos_para_actualizar");

        Terminal t = terminals.findById(id)
                .orElseThrow(() -> new NotFoundException("terminal_no_encontrado"));

        if ("ONLINE".equals(t.status())) throw new ConflictException("terminal_online");

        terminals.update(id, req.model(), req.appVersion());
        return terminals.findById(id).orElseThrow();
    }

    public void deleteTerminal(UUID id) {
        Terminal t = terminals.findById(id)
                .orElseThrow(() -> new NotFoundException("terminal_no_encontrado"));
        if ("ONLINE".equals(t.status())) throw new ConflictException("terminal_online");
        terminals.delete(id);
    }

    public String setMaintenanceStatus(UUID id, String newStatus) {
        Terminal t = terminals.findById(id)
                .orElseThrow(() -> new NotFoundException("terminal_no_encontrado"));
        if ("ONLINE".equals(t.status())) throw new ConflictException("terminal_online");
        terminals.setStatus(id, newStatus);
        return newStatus;
    }

    private void assertAccess(Jwt jwt, UUID terminalOrgId) {
        if (isAdmin(jwt)) return;
        if (!terminalOrgId.equals(loadCaller(jwt).orgId())) throw new ForbiddenException("sin_permiso");
    }

    private boolean isAdmin(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        return groups != null && groups.contains("ADMIN");
    }

    private User loadCaller(Jwt jwt) {
        return users.findByCognitoSub(jwt.getSubject())
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
    }
}
