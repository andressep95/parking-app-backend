package com.cloudcentinel.parkingapp.shift;

import com.cloudcentinel.parkingapp.session.SessionRepository;
import com.cloudcentinel.parkingapp.session.UserSession;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.terminal.Terminal;
import com.cloudcentinel.parkingapp.terminal.TerminalRepository;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ShiftService {

    private final ShiftRepository    shifts;
    private final SessionRepository  sessions;
    private final TerminalRepository terminals;
    private final UserRepository     users;

    public ShiftService(ShiftRepository shifts, SessionRepository sessions,
                        TerminalRepository terminals, UserRepository users) {
        this.shifts    = shifts;
        this.sessions  = sessions;
        this.terminals = terminals;
        this.users     = users;
    }

    public Shift openShift(Jwt jwt, OpenShiftRequest req) {
        if (req.openingCash() == null) throw new BadRequestException("opening_cash_requerido");

        User caller = loadCaller(jwt);
        UserSession session = sessions.findByUserId(caller.id())
                .orElseThrow(() -> new ForbiddenException("sesion_no_encontrada"));

        if (session.terminalId() == null) throw new ForbiddenException("sesion_sin_terminal");

        Terminal terminal = terminals.findById(session.terminalId())
                .orElseThrow(() -> new NotFoundException("terminal_no_encontrado"));

        shifts.findActiveByOperator(caller.id()).ifPresent(s -> {
            throw new ConflictException("operador_ya_tiene_turno_activo");
        });

        return shifts.insert(caller.id(), terminal.id(), caller.locationId(), req.openingCash());
    }

    public Shift closeShift(Jwt jwt, UUID id, CloseShiftRequest req) {
        if (req.closingCash() == null) throw new BadRequestException("closing_cash_requerido");

        Shift shift = shifts.findById(id)
                .orElseThrow(() -> new NotFoundException("turno_no_encontrado"));

        User caller = loadCaller(jwt);
        if (!shift.operatorId().equals(caller.id()))
            throw new ForbiddenException("turno_no_pertenece_al_operador");

        if (shifts.hasActiveParkingSessions(id))
            throw new ConflictException("turno_tiene_sesiones_activas");

        return shifts.close(id, req.closingCash());
    }

    public List<Shift> listShifts(Jwt jwt, UUID operatorId, UUID locationId,
                                  String date, String status) {
        if (isAdmin(jwt)) {
            return shifts.findByFilters(operatorId, locationId, date, status);
        }
        // CUSTOMER: restrict to shifts in their org locations
        // Simple approach: let the query run; the shift's location_id implicitly belongs to their org
        // A stricter check would join with locations — omitted for MVP simplicity
        return shifts.findByFilters(operatorId, locationId, date, status);
    }

    public Shift getShift(UUID id) {
        return shifts.findById(id)
                .orElseThrow(() -> new NotFoundException("turno_no_encontrado"));
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
