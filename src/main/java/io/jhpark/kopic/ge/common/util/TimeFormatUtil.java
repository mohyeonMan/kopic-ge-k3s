package io.jhpark.kopic.ge.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeFormatUtil {

    private static final DateTimeFormatter DEFAULT_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();

    public static String now() {
        return format(nowInstant());
    }

    public static Instant nowInstant() {
        return Instant.now();
    }

    public static String format(Instant instant) {
        return DEFAULT_FORMATTER
            .withZone(DEFAULT_ZONE_ID)
            .format(instant);
    }

    public static Instant parse(String value) {
        LocalDateTime dateTime = LocalDateTime.parse(value, DEFAULT_FORMATTER);
        return dateTime.atZone(DEFAULT_ZONE_ID).toInstant();
    }
}
