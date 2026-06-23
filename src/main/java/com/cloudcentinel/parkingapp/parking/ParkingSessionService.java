package com.cloudcentinel.parkingapp.parking;

import com.cloudcentinel.parkingapp.session.SessionRepository;
import com.cloudcentinel.parkingapp.session.UserSession;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.shift.Shift;
import com.cloudcentinel.parkingapp.shift.ShiftRepository;
import com.cloudcentinel.parkingapp.terminal.Terminal;
import com.cloudcentinel.parkingapp.terminal.TerminalRepository;
import com.cloudcentinel.parkingapp.transaction.TransactionRepository;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import com.cloudcentinel.parkingapp.vehicle.VehicleRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
public class ParkingSessionService {

    private static final Set<String> VALID_VEHICLE_TYPES =
            Set.of("CAR", "MOTORCYCLE", "PICKUP", "BUS");
    private static final Set<String> VALID_PAYMENT_METHODS =
            Set.of("TUU_CARD", "TUU_CASH");

    private final ParkingSessionRepository sessions;
    private final ShiftRepository          shifts;
    private final SessionRepository        userSessions;
    private final TerminalRepository       terminals;
    private final VehicleRepository        vehicles;
    private final TransactionRepository    transactions;
    private final UserRepository           users;

    public ParkingSessionService(ParkingSessionRepository sessions,
                                 ShiftRepository shifts,
                                 SessionRepository userSessions,
                                 TerminalRepository terminals,
                                 VehicleRepository vehicles,
                                 TransactionRepository transactions,
                                 UserRepository users) {
        this.sessions     = sessions;
        this.shifts       = shifts;
        this.userSessions = userSessions;
        this.terminals    = terminals;
        this.vehicles     = vehicles;
        this.transactions = transactions;
        this.users        = users;
    }

    public record CreationResult(ParkingSessionResponse session, boolean created) {}

    public CreationResult createSession(Jwt jwt, CreateParkingSessionRequest req) {
        if (req.id() == null || req.plate() == null || req.vehicleType() == null
                || req.entryAt() == null || req.shiftId() == null || req.tariffSnapshot() == null) {
            throw new BadRequestException("campos_requeridos_faltantes");
        }
        if (!VALID_VEHICLE_TYPES.contains(req.vehicleType())) {
            throw new BadRequestException("vehicle_type_invalido");
        }

        User caller = loadCaller(jwt);
        UserSession userSession = userSessions.findByUserId(caller.id())
                .orElseThrow(() -> new ForbiddenException("sesion_no_encontrada"));
        Terminal terminal = terminals.findById(userSession.terminalId())
                .orElseThrow(() -> new NotFoundException("terminal_no_encontrado"));

        // idempotency: same UUID = same request
        if (sessions.existsById(req.id())) {
            ParkingSessionRepository.SessionRow row = sessions.findById(req.id()).orElseThrow();
            return new CreationResult(toResponse(row, req.tariffSnapshot(), null, null), false);
        }

        Shift shift = shifts.findById(req.shiftId())
                .filter(s -> s.operatorId().equals(caller.id()) && "ACTIVE".equals(s.status()))
                .orElseThrow(() -> new ConflictException("sin_turno_activo"));

        if (sessions.hasActivePlateInLocation(req.plate(), caller.locationId())) {
            throw new ConflictException("vehiculo_ya_ingresado");
        }

        vehicles.upsert(req.plate(), req.vehicleType());

        Instant entryAt = Instant.ofEpochSecond(req.entryAt());
        sessions.insert(req.id(), caller.locationId(), req.plate(), req.vehicleType(),
                entryAt, caller.id(), terminal.id(), shift.id(),
                req.tariffSnapshot().toJson());

        ParkingSessionRepository.SessionRow row = sessions.findById(req.id()).orElseThrow();
        return new CreationResult(toResponse(row, req.tariffSnapshot(), null, null), true);
    }

    @Transactional
    public ParkingSessionResponse checkout(Jwt jwt, java.util.UUID sessionId, CheckoutRequest req) {
        if (req.transactionId() == null || req.exitAt() == null || req.durationMinutes() == null
                || req.calculatedAmount() == null || req.paymentMethod() == null) {
            throw new BadRequestException("campos_requeridos_faltantes");
        }
        if (!VALID_PAYMENT_METHODS.contains(req.paymentMethod())) {
            throw new BadRequestException("payment_method_invalido");
        }

        ParkingSessionRepository.SessionRow session = sessions.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("sesion_no_encontrada"));

        // idempotency: transaction already exists → already processed successfully
        if (transactions.existsById(req.transactionId())) {
            return toResponse(session, null, req.transactionId(), req.paymentMethod());
        }

        if (!"ACTIVE".equals(session.status())) {
            throw new ConflictException("sesion_ya_cerrada");
        }

        Instant exitAt = Instant.ofEpochSecond(req.exitAt());
        sessions.complete(sessionId, exitAt, req.durationMinutes(), req.calculatedAmount());
        transactions.insert(req.transactionId(), sessionId, session.shiftId(),
                req.calculatedAmount(), req.paymentMethod(), req.tuuReference());

        ParkingSessionRepository.SessionRow updated = sessions.findById(sessionId).orElseThrow();
        return toResponse(updated, null, req.transactionId(), req.paymentMethod());
    }

    private ParkingSessionResponse toResponse(ParkingSessionRepository.SessionRow row,
                                              TariffSnapshot snapshot,
                                              java.util.UUID transactionId,
                                              String paymentMethod) {
        return new ParkingSessionResponse(
                row.id(), row.plate(), row.vehicleType(), row.status(),
                row.entryAt(), row.exitAt(), row.durationMinutes(), row.calculatedAmount(),
                row.locationId(), row.operatorId(), row.terminalId(), row.shiftId(),
                snapshot, transactionId, paymentMethod
        );
    }

    private User loadCaller(Jwt jwt) {
        return users.findByCognitoSub(jwt.getSubject())
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
    }
}
