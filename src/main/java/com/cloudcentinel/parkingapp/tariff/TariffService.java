package com.cloudcentinel.parkingapp.tariff;

import com.cloudcentinel.parkingapp.location.Location;
import com.cloudcentinel.parkingapp.location.LocationRepository;
import com.cloudcentinel.parkingapp.shared.exception.BadRequestException;
import com.cloudcentinel.parkingapp.shared.exception.ConflictException;
import com.cloudcentinel.parkingapp.shared.exception.ForbiddenException;
import com.cloudcentinel.parkingapp.shared.exception.NotFoundException;
import com.cloudcentinel.parkingapp.user.User;
import com.cloudcentinel.parkingapp.user.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TariffService {

    private static final Set<String> VALID_VEHICLE_TYPES =
            Set.of("CAR", "MOTORCYCLE", "PICKUP", "BUS");
    private static final Set<String> VALID_TARIFF_TYPES =
            Set.of("PER_MINUTE", "BRACKET", "FLAT_ENTRY");

    private final TariffRepository   tariffs;
    private final LocationRepository locations;
    private final UserRepository     users;

    public TariffService(TariffRepository tariffs,
                         LocationRepository locations,
                         UserRepository users) {
        this.tariffs   = tariffs;
        this.locations = locations;
        this.users     = users;
    }

    @Transactional
    public Tariff createTariff(Jwt jwt, CreateTariffRequest req) {
        if (req.locationId() == null || req.vehicleType() == null || req.tariffType() == null) {
            throw new BadRequestException("campos_requeridos_faltantes");
        }
        if (!VALID_VEHICLE_TYPES.contains(req.vehicleType())) {
            throw new BadRequestException("vehicle_type_invalido");
        }
        if (!VALID_TARIFF_TYPES.contains(req.tariffType())) {
            throw new BadRequestException("tariff_type_invalido");
        }

        validateByType(req);

        if (req.maxCharge() != null && req.maxCharge().compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("precio_invalido");
        }

        Location loc = locations.findById(req.locationId())
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());

        tariffs.deactivatePrevious(req.locationId(), req.vehicleType());

        return tariffs.insert(req.locationId(), req);
    }

    public List<Tariff> listTariffs(Jwt jwt, UUID locationId, Boolean active) {
        if (locationId == null) throw new BadRequestException("location_id_requerido");

        Location loc = locations.findById(locationId)
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());

        return tariffs.findByLocationId(locationId, active);
    }

    public Tariff getTariff(Jwt jwt, UUID id) {
        Tariff t = tariffs.findById(id)
                .orElseThrow(() -> new NotFoundException("tarifa_no_encontrada"));
        Location loc = locations.findById(t.locationId())
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());
        return t;
    }

    public Tariff deactivateTariff(Jwt jwt, UUID id) {
        Tariff t = tariffs.findById(id)
                .orElseThrow(() -> new NotFoundException("tarifa_no_encontrada"));
        if (!t.isActive()) throw new ConflictException("tarifa_ya_inactiva");

        Location loc = locations.findById(t.locationId())
                .orElseThrow(() -> new NotFoundException("locacion_no_encontrada"));
        assertAccess(jwt, loc.orgId());

        tariffs.deactivate(id);
        return tariffs.findById(id).orElseThrow();
    }

    private void validateByType(CreateTariffRequest req) {
        switch (req.tariffType()) {
            case "PER_MINUTE" -> {
                if (req.pricePerMinute() == null)
                    throw new BadRequestException("campos_requeridos_faltantes");
                if (req.pricePerMinute().compareTo(BigDecimal.ZERO) < 0)
                    throw new BadRequestException("precio_invalido");
            }
            case "BRACKET" -> {
                if (req.brackets() == null || req.brackets().isEmpty())
                    throw new BadRequestException("tramos_requeridos");
                validateBrackets(req.brackets());
            }
            case "FLAT_ENTRY" -> {
                if (req.flatAmount() == null)
                    throw new BadRequestException("campos_requeridos_faltantes");
                if (req.flatAmount().compareTo(BigDecimal.ZERO) < 0)
                    throw new BadRequestException("precio_invalido");
            }
        }
    }

    private void validateBrackets(List<CreateTariffRequest.BracketRequest> brackets) {
        for (CreateTariffRequest.BracketRequest br : brackets) {
            if (br.position() == null || br.fromMinute() == null || br.pricePerMinute() == null) {
                throw new BadRequestException("tramo_invalido");
            }
            if (br.fromMinute() < 0 || br.pricePerMinute().compareTo(BigDecimal.ZERO) < 0) {
                throw new BadRequestException("tramo_invalido");
            }
            if (br.toMinute() != null && br.toMinute() <= br.fromMinute()) {
                throw new BadRequestException("tramo_invalido");
            }
        }

        List<CreateTariffRequest.BracketRequest> sorted = brackets.stream()
                .sorted(Comparator.comparingInt(CreateTariffRequest.BracketRequest::fromMinute))
                .toList();

        for (int i = 0; i < sorted.size() - 1; i++) {
            CreateTariffRequest.BracketRequest curr = sorted.get(i);
            CreateTariffRequest.BracketRequest next = sorted.get(i + 1);
            if (curr.toMinute() == null) {
                throw new BadRequestException("tramos_solapados");
            }
            if (next.fromMinute() < curr.toMinute()) {
                throw new BadRequestException("tramos_solapados");
            }
        }
    }

    private void assertAccess(Jwt jwt, UUID locationOrgId) {
        if (isAdmin(jwt)) return;
        if (!locationOrgId.equals(loadCaller(jwt).orgId())) throw new ForbiddenException("sin_permiso");
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
