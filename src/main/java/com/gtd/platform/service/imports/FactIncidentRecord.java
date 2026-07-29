package com.gtd.platform.service.imports;

/**
 * A fully-resolved, validated row ready for the bulk {@code fact_incident}
 * upsert. Carries {@code stagingIncidentId} purely so the orchestrator can
 * log an {@link RejectedRow} back to its source row if a batch write skips
 * this eventid (APPEND mode duplicate) - it is not written to fact_incident.
 */
public record FactIncidentRecord(
        long stagingIncidentId,
        String eventId,
        long datasetVersionId,
        long countryId,
        long regionId,
        long attackTypeId,
        long weaponTypeId,
        long targetTypeId,
        long organizationId,
        int eventYear,
        Integer eventMonth,
        Integer eventDay,
        String provstate,
        String city,
        Double latitude,
        Double longitude,
        String summary,
        String targetDescription,
        Boolean success,
        Boolean suicide,
        Boolean individualPerpetrator,
        Boolean propertyDamage,
        Integer nkill,
        Integer nwound,
        String motive
) {
}
