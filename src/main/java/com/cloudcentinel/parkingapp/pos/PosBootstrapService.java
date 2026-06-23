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
        // 1. Resolve caller and their active session
        User caller = loadCaller(jwt);
        UserSession session = sessions.findByUserId(caller.id())
                .orElseThrow(() -> new ForbiddenException("sesion_no_encontrada"));

        // 2. Verify terminal is registered and matches the caller's session
        TerminalInfo terminal = findTerminalBySerial(serialNumber)
                .orElseThrow(() -> new NotFoundException("terminal_no_registrado"));
        if (!terminal.id().equals(session.terminalId())) {
            throw new ForbiddenException("terminal_no_pertenece_a_sesion");
        }

        // 3. Load location and org from the operator's assigned location
        if (caller.locationId() == null) {
            throw new BadRequestException("operator_sin_locacion");
        }
        LocationOrg locationOrg = findLocationOrg(caller.locationId())
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        if (!"ACTIVE".equals(locationOrg.locationStatus())) {
            throw new BadRequestException("locacion_no_disponible");
        }
        if (!terminal.orgId().equals(locationOrg.orgId())) {
            throw new ForbiddenException("terminal_no_pertenece_a_org");
        }

        // 4. Query tariffs, active sessions and current shift
        List<BootstrapTariff>  tariffs        = loadActiveTariffs(caller.locationId());
        List<BootstrapSession> activeSessions = loadActiveSessions(caller.locationId());
        BootstrapShift         currentShift   = loadCurrentShift(caller.id()).orElse(null);

        return new BootstrapResponse(
                new BootstrapTerminal(terminal.id(), terminal.serialNumber(), terminal.model()),
                new BootstrapOrganization(locationOrg.orgId(), locationOrg.orgName(),
                        locationOrg.rutCompany(), locationOrg.orgEmail(), locationOrg.phoneNumber()),
                new BootstrapLocation(locationOrg.locationId(), locationOrg.locationName(),
                        locationOrg.address(), locationOrg.city(),
                        locationOrg.timezone(), locationOrg.capacity()),
                tariffs,
                activeSessions,
                currentShift
        );
    }

    private Optional<TerminalInfo> findTerminalBySerial(String serialNumber) {
        return jdbc.sql("""
                SELECT id, serial_number, model, org_id
                FROM terminals WHERE serial_number = :sn
                """)
                .param("sn", serialNumber)
                .query((rs, rn) -> new TerminalInfo(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("serial_number"),
                        rs.getString("model"),
                        UUID.fromString(rs.getString("org_id"))
                ))
                .optional();
    }

    private Optional<LocationOrg> findLocationOrg(UUID locationId) {
        return jdbc.sql("""
                SELECT l.id AS location_id, l.location_name, l.address, l.city,
                       l.capacity, l.timezone, l.location_status::text AS location_status,
                       o.id AS org_id, o.org_name, o.rut_company, o.org_email, o.phone_number
                FROM locations l
                JOIN organizations o ON l.org_id = o.id
                WHERE l.id = :locationId
                """)
                .param("locationId", locationId)
                .query((rs, rn) -> new LocationOrg(
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

    private record TerminalInfo(UUID id, String serialNumber, String model, UUID orgId) {}

    private record LocationOrg(
            UUID locationId, String locationName, String address,
            String city, int capacity, String timezone, String locationStatus,
            UUID orgId, String orgName, String rutCompany,
            String orgEmail, String phoneNumber
    ) {}
}
