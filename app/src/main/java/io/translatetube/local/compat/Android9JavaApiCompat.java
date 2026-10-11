package io.translatetube.local.compat;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Backports selected Java library methods referenced by NewPipeExtractor to Android 9 (API 28).
 *
 * NewPipe's bytecode is rewritten at build time to call these methods instead of platform APIs
 * which were added after Android 9.
 */
public final class Android9JavaApiCompat {
    private Android9JavaApiCompat() {}

    public static String decode(String value, Charset charset) {
        try {
            return URLDecoder.decode(value, charset.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException("Unsupported URL decoding charset: " + charset.name(), e);
        }
    }

    public static String encode(String value, Charset charset) {
        try {
            return URLEncoder.encode(value, charset.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException("Unsupported URL encoding charset: " + charset.name(), e);
        }
    }

    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return Collectors.collectingAndThen(
            Collectors.toList(),
            Collections::unmodifiableList
        );
    }

    public static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) return true;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) return false;
            i += Character.charCount(codePoint);
        }
        return true;
    }
}
