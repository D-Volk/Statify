package ru.dvolk.statify.forge.format;

public final class DurationFormat {

    private final String suffixDay;
    private final String suffixHour;
    private final String suffixMinute;
    private final String suffixSecond;
    private final String separator;
    private final String zeroText;

    public DurationFormat(String suffixDay, String suffixHour, String suffixMinute,
                          String suffixSecond, String separator, String zeroText) {
        this.suffixDay = suffixDay;
        this.suffixHour = suffixHour;
        this.suffixMinute = suffixMinute;
        this.suffixSecond = suffixSecond;
        this.separator = separator;
        this.zeroText = zeroText;
    }

    public String format(long seconds) {
        if (seconds <= 0) return zeroText;
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) append(sb, d, suffixDay);
        if (h > 0) append(sb, h, suffixHour);
        if (m > 0) append(sb, m, suffixMinute);
        if (s > 0 || sb.length() == 0) append(sb, s, suffixSecond);
        return sb.toString();
    }

    private void append(StringBuilder sb, long value, String suffix) {
        if (sb.length() > 0) sb.append(separator);
        sb.append(value).append(suffix);
    }
}
