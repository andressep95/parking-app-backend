package com.cloudcentinel.parkingapp.pos;

import com.cloudcentinel.parkingapp.pos.BootstrapResponse.*;
import com.cloudcentinel.parkingapp.session.SessionRepository;
import com.cloudcentinel.parkingapp.session.UserSession;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PosBootstrapService {

    private final JdbcClient        jdbc;
    private final SessionRepository sessions;
    private final UserRepository    users;

    public PosBootstrapService(JdbcClient jdbc, SessionRepository sessions, UserRepository users) {
        this.jdbc     = jdbc;
        this.sessions = sessions;
        this.users    = users;
    }

    public BootstrapResponse bootstrap(Jwt jwt, String serialNumber) {
        // 1. Resolve terminal + location + org from serialNumber
        TerminalLocationOrg tlo = findTerminalLocationOrg(serialNumber)
                .orElseThrow(() -> new NotFoundException("terminal_no_registrado"));

        if (!"ACTIVE".equals(tlo.locationStatus())) {
            throw new BadRequestException("locacion_no_disponible");
        }

        // 2. Validate that the caller's session terminal matches
        User caller = loadCaller(jwt);
        UserSession session = sessions.findByUserId(caller.id())
                .orElseThrow(() -> new ForbiddenException("sesion_no_encontrada"));
        if (!tlo.terminalId().equals(session.terminalId())) {
            throw new ForbiddenException("terminal_no_pertenece_a_sesion");
        }

        // 3. Query tariffs
        List<BootstrapTariff> tariffs = loadActiveTariffs(tlo.locationId());

        // 4. Query active sessions
        List<BootstrapSession> activeSessions = loadActiveSessions(tlo.locationId());

        // 5. Query current shift for this operator
        BootstrapShift currentShift = loadCurrentShift(caller.id()).orElse(null);

        return new BootstrapResponse(
                new BootstrapTerminal(tlo.terminalId(), tlo.serialNumber(), tlo.model()),
                new BootstrapOrganization(tlo.orgId(), tlo.orgName(), tlo.rutCompany(),
                        tlo.orgEmail(), tlo.phoneNumber()),
                new BootstrapLocation(tlo.locationId(), tlo.locationName(), tlo.address(),
                        tlo.city(), tlo.timezone(), tlo.capacity()),
                tariffs,
                activeSessions,
                currentShift
        );
    }

    private Optional<TerminalLocationOrg> findTerminalLocationOrg(String serialNumber) {
        return jdbc.sql("""
                SELECT t.id AS terminal_id, t.serial_number, t.model,
                       l.id AS location_id, l.location_name, l.address, l.city,
                       l.capacity, l.timezone, l.location_status::text AS location_status,
                       o.id AS org_id, o.org_name, o.rut_company, o.org_email, o.phone_number
                FROM terminals t
                JOIN locations l ON t.location_id = l.id
                JOIN organizations o ON l.org_id = o.id
                WHERE t.serial_number = :sn
                """)
                .param("sn", serialNumber)
                .query((rs, rn) -> new TerminalLocationOrg(
                        UUID.fromString(rs.getString("terminal_id")),
                        rs.getString("serial_number"),
                        rs.getString("model"),
                        UUID.fromString(rs.getString("location_id")),
                        rs.getString("location_name"),
                        rs.getString("address"),
                        rs.getString("city"),
                        rs.getInt("capacity"),
                        rs.getString("timezone"),
                        rs.getString("location_status"),
                        UUID.fromString(rs.getString("org_id")),
                        rs.getString("org_name"),
                        rs.getString("rut_company"),
                        rs.getString("org_email"),
                        rs.getString("phone_number")
                ))
                .optional();
    }

    private List<BootstrapTariff> loadActiveTariffs(UUID locationId) {
        return jdbc.sql("""
                SELECT id, vehicle_type::text, name,
                       price_per_hour, minimum_charge, grace_minutes
                FROM tariffs
                WHERE location_id = :locationId AND is_active = true
                ORDER BY vehicle_type
                """)
                .param("locationId", locationId)
                .query((rs, rn) -> new BootstrapTariff(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("vehicle_type"),
                        rs.getString("name"),
                        rs.getBigDecimal("price_per_hour"),
                        rs.getBigDecimal("minimum_charge"),
                        rs.getInt("grace_minutes")
                ))
                .list();
    }

    private List<BootstrapSession> loadActiveSessions(UUID locationId) {
        return jdbc.sql("""
                SELECT ps.id, ps.plate, ps.vehicle_type::text,
                       EXTRACT(EPOCH FROM ps.entry_at)::bigint AS entry_at_epoch,
                       u.given_name || ' ' || u.family_name AS operator_name
                FROM parking_sessions ps
                JOIN users u ON ps.operator_id_in = u.id
                WHERE ps.location_id = :locationId AND ps.status = 'ACTIVE'
                ORDER BY ps.entry_at
                """)
                .param("locationId", locationId)
                .query((rs, rn) -> new BootstrapSession(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("plate"),
                        rs.getString("vehicle_type"),
                        rs.getLong("entry_at_epoch"),
                        rs.getString("operator_name")
                ))
                .list();
    }

    private Optional<BootstrapShift> loadCurrentShift(UUID operatorId) {
        return jdbc.sql("""
                SELECT id, EXTRACT(EPOCH FROM started_at)::bigint AS started_at_epoch, opening_cash
                FROM shifts
                WHERE operator_id = :operatorId AND status = 'ACTIVE'
                """)
                .param("operatorId", operatorId)
                .query((rs, rn) -> new BootstrapShift(
                        UUID.fromString(rs.getString("id")),
                        rs.getLong("started_at_epoch"),
                        rs.getBigDecimal("opening_cash")
                ))
                .optional();
    }

    private User loadCaller(Jwt jwt) {
        return users.findByCognitoSub(jwt.getSubject())
                .orElseThrow(() -> new NotFoundException("usuario_no_encontrado"));
    }

    private record TerminalLocationOrg(
            UUID terminalId, String serialNumber, String model,
            UUID locationId, String locationName, String address,
            String city, int capacity, String timezone, String locationStatus,
            UUID orgId, String orgName, String rutCompany,
            String orgEmail, String phoneNumber
    ) {}
}
