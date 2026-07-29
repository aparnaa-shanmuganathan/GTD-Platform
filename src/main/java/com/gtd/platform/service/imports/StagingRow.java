package com.gtd.platform.service.imports;

/** The subset of one {@code staging_incident} row's raw TEXT columns that normalization actually needs. */
public record StagingRow(
        long id,
        String eventId,
        String iyear,
        String imonth,
        String iday,
        String countryTxt,
        String regionTxt,
        String provstate,
        String city,
        String latitude,
        String longitude,
        String summary,
        String target1,
        String attackType1Txt,
        String weaponType1Txt,
        String targetType1Txt,
        String gname,
        String nkill,
        String nwound,
        String success,
        String suicide,
        String individual,
        String property,
        String motive
) {
}
