package com.cloudcentinel.parkingapp.location;

public record UpdateLocationRequest(
        String  locationName,
        String  address,
        String  city,
        Integer capacity,
        Integer maxOperators,
        String  timezone
) {
    public boolean hasAnyField() {
        return locationName != null || address != null || city != null
                || capacity != null || maxOperators != null || timezone != null;
    }
}
