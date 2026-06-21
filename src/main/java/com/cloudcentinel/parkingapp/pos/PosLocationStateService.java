package com.cloudcentinel.parkingapp.pos;

import com.cloudcentinel.parkingapp.pos.LocationStateResponse.*;
import com.cloudcentinel.parkingapp.session.SessionRepository;
import com.cloudcentinel.parkingapp.session.UserSession;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.terminal.Terminal;
import com.cloudcentinel.parkingapp.terminal.TerminalRepository;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PosLocationStateService {

    private final JdbcClient        jdbc;
    private final SessionRepository sessions;
    private final TerminalRepository terminals;
    private final UserRepository    users;

    public PosLocationStateService(JdbcClient jdbc, SessionRepository sessions,
                                   TerminalRepository terminals, UserRepository users) {
        this.jdbc      = jdbc;
        this.sessions  = sessions;
        this.terminals = terminals;
        this.users     = users;
    }

    public LocationStateResponse getLocationState(Jwt jwt, Long since) {
        if (since == null) throw new BadRequestException("since_requerido");

        User caller = loadCaller(jwt);
        UserSession session = sessions.findByUserId(caller.id())
                .orElseThrow(() -> new ForbiddenException("sesion_no_encontrada"));
        Terminal terminal = terminals.findById(session.terminalId())
                .orElseThrow(() -> new NotFoundException("terminal_no_encontrado"));

        UUID locationId = terminal.locationId();
        long asOf = Instant.now().getEpochSecond();

        List<NewSession> newSessions = jdbc.sql("""
                SELECT ps.id, ps.plate, ps.vehicle_type::text,
                       EXTRACT(EPOCH FROM ps.entry_at)::bigint AS entry_at_epoch,
                       u.given_name || ' ' || u.family_name AS operator_name
                FROM parking_sessions ps
                JOIN users u ON ps.operator_id_in = u.id
                WHERE ps.location_id = :locationId
                  AND ps.status = 'ACTIVE'
                  AND EXTRACT(EPOCH FROM ps.entry_at)::bigint > :since
                ORDER BY ps.entry_at
                """)
                .param("locationId", locationId)
                .param("since",      since)
                .query((rs, rn) -> new NewSession(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("plate"),
                        rs.getString("vehicle_type"),
                        rs.getLong("entry_at_epoch"),
                        rs.getString("operator_name")
                ))
                .list();

        List<ClosedSession> closedSessions = jdbc.sql("""
                SELECT id, plate, status::text,
                       EXTRACT(EPOCH FROM exit_at)::bigint AS exit_at_epoch,
                       calculated_amount
                FROM parking_sessions
                WHERE location_id = :locationId
                  AND status IN ('COMPLETED', 'CANCELLED')
                  AND EXTRACT(EPOCH FROM exit_at)::bigint > :since
                ORDER BY exit_at
                """)
                .param("locationId", locationId)
                .param("since",      since)
                .query((rs, rn) -> new ClosedSession(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("plate"),
                        rs.getLong("exit_at_epoch"),
                        rs.getString("status"),
                        rs.getBigDecimal("calculated_amount")
                ))
                .list();

        return new LocationStateResponse(asOf, newSessions, closedSessions);
    }

    private User loadCaller(Jwt jwt) {
        return users.findByCognitoSub(jwt.getSubject())
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
    }
}
