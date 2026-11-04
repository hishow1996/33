package io.translatetube.local.compat;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** Backports selected Java library methods referenced by NewPipeExtractor to Android 9 (API 28). */
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
        return Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList);
    }

    public static <T> Collector<T, ?, Set<T>> toUnmodifiableSet() {
        return Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet);
    }

    public static <T, K, V> Collector<T, ?, Map<K,V>> toUnmodifiableMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends V> valueMapper) {
        return Collectors.collectingAndThen(
            Collectors.toMap(keyMapper, valueMapper), Collections::unmodifiableMap);
    }

    public static <T, K, V> Collector<T, ?, Map<K,V>> toUnmodifiableMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends V> valueMapper,
            BinaryOperator<V> mergeFunction) {
        return Collectors.collectingAndThen(
            Collectors.toMap(keyMapper, valueMapper, mergeFunction), Collections::unmodifiableMap);
    }

    // Java 9 collection factories mirror their erased JVM descriptors so NewPipe bytecode can be redirected.
    @SafeVarargs public static <T> List<T> listOf(T... values) { return immutableList(values); }
    public static <T> List<T> listOf() { return Collections.emptyList(); }
    public static <T> List<T> listOf(T a) { return immutableList(new Object[]{a}); }
    public static <T> List<T> listOf(T a,T b) { return immutableList(new Object[]{a,b}); }
    public static <T> List<T> listOf(T a,T b,T c) { return immutableList(new Object[]{a,b,c}); }
    public static <T> List<T> listOf(T a,T b,T c,T d) { return immutableList(new Object[]{a,b,c,d}); }
    public static <T> List<T> listOf(T a,T b,T c,T d,T e) { return immutableList(new Object[]{a,b,c,d,e}); }
    public static <T> List<T> listOf(T a,T b,T c,T d,T e,T f) { return immutableList(new Object[]{a,b,c,d,e,f}); }
    public static <T> List<T> listOf(T a,T b,T c,T d,T e,T f,T g) { return immutableList(new Object[]{a,b,c,d,e,f,g}); }
    public static <T> List<T> listOf(T a,T b,T c,T d,T e,T f,T g,T h) { return immutableList(new Object[]{a,b,c,d,e,f,g,h}); }
    public static <T> List<T> listOf(T a,T b,T c,T d,T e,T f,T g,T h,T i) { return immutableList(new Object[]{a,b,c,d,e,f,g,h,i}); }
    public static <T> List<T> listOf(T a,T b,T c,T d,T e,T f,T g,T h,T i,T j) { return immutableList(new Object[]{a,b,c,d,e,f,g,h,i,j}); }

    @SafeVarargs public static <T> Set<T> setOf(T... values) { return immutableSet(values); }
    public static <T> Set<T> setOf() { return Collections.emptySet(); }
    public static <T> Set<T> setOf(T a) { return immutableSet(new Object[]{a}); }
    public static <T> Set<T> setOf(T a,T b) { return immutableSet(new Object[]{a,b}); }
    public static <T> Set<T> setOf(T a,T b,T c) { return immutableSet(new Object[]{a,b,c}); }
    public static <T> Set<T> setOf(T a,T b,T c,T d) { return immutableSet(new Object[]{a,b,c,d}); }
    public static <T> Set<T> setOf(T a,T b,T c,T d,T e) { return immutableSet(new Object[]{a,b,c,d,e}); }
    public static <T> Set<T> setOf(T a,T b,T c,T d,T e,T f) { return immutableSet(new Object[]{a,b,c,d,e,f}); }
    public static <T> Set<T> setOf(T a,T b,T c,T d,T e,T f,T g) { return immutableSet(new Object[]{a,b,c,d,e,f,g}); }
    public static <T> Set<T> setOf(T a,T b,T c,T d,T e,T f,T g,T h) { return immutableSet(new Object[]{a,b,c,d,e,f,g,h}); }
    public static <T> Set<T> setOf(T a,T b,T c,T d,T e,T f,T g,T h,T i) { return immutableSet(new Object[]{a,b,c,d,e,f,g,h,i}); }
    public static <T> Set<T> setOf(T a,T b,T c,T d,T e,T f,T g,T h,T i,T j) { return immutableSet(new Object[]{a,b,c,d,e,f,g,h,i,j}); }

    public static <K,V> Map<K,V> mapOf() { return Collections.emptyMap(); }
    @SafeVarargs public static <K,V> Map<K,V> mapOf(Object... pairs) { return immutableMapPairs(pairs); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1) { return immutableMapPairs(new Object[]{k1,v1}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2) { return immutableMapPairs(new Object[]{k1,v1,k2,v2}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3,K k4,V v4) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3,k4,v4}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3,K k4,V v4,K k5,V v5) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3,k4,v4,k5,v5}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3,K k4,V v4,K k5,V v5,K k6,V v6) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3,k4,v4,k5,v5,k6,v6}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3,K k4,V v4,K k5,V v5,K k6,V v6,K k7,V v7) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3,k4,v4,k5,v5,k6,v6,k7,v7}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3,K k4,V v4,K k5,V v5,K k6,V v6,K k7,V v7,K k8,V v8) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3,k4,v4,k5,v5,k6,v6,k7,v7,k8,v8}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3,K k4,V v4,K k5,V v5,K k6,V v6,K k7,V v7,K k8,V v8,K k9,V v9) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3,k4,v4,k5,v5,k6,v6,k7,v7,k8,v8,k9,v9}); }
    public static <K,V> Map<K,V> mapOf(K k1,V v1,K k2,V v2,K k3,V v3,K k4,V v4,K k5,V v5,K k6,V v6,K k7,V v7,K k8,V v8,K k9,V v9,K k10,V v10) { return immutableMapPairs(new Object[]{k1,v1,k2,v2,k3,v3,k4,v4,k5,v5,k6,v6,k7,v7,k8,v8,k9,v9,k10,v10}); }

    @SafeVarargs public static <K,V> Map<K,V> mapOfEntries(Map.Entry<? extends K,? extends V>... entries) {
        LinkedHashMap<K,V> result = new LinkedHashMap<>();
        for (Map.Entry<? extends K,? extends V> e : entries) {
            if (e == null || e.getKey() == null || e.getValue() == null) throw new NullPointerException("Map.ofEntries does not allow null");
            if (result.containsKey(e.getKey())) throw new IllegalArgumentException("duplicate key");
            result.put(e.getKey(),e.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    public static <T> List<T> listCopyOf(Collection<? extends T> values) { return immutableList(values.toArray()); }
    public static <T> Set<T> setCopyOf(Collection<? extends T> values) { return immutableSet(values.toArray()); }
    public static <K,V> Map<K,V> mapCopyOf(Map<? extends K,? extends V> values) {
        if (values.containsKey(null) || values.containsValue(null)) throw new NullPointerException();
        return Collections.unmodifiableMap(new LinkedHashMap<K,V>(values));
    }

    public static <T> T requireNonNullElse(T value, T defaultValue) {
        if (value != null) return value;
        if (defaultValue == null) throw new NullPointerException();
        return defaultValue;
    }
    public static <T> T requireNonNullElseGet(T value, Supplier<? extends T> supplier) {
        if (value != null) return value;
        if (supplier == null) throw new NullPointerException();
        T fallback = supplier.get();
        if (fallback == null) throw new NullPointerException();
        return fallback;
    }

    private static <T> List<T> immutableList(Object[] values) {
        ArrayList<T> result = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value == null) throw new NullPointerException("List.of does not allow null");
            @SuppressWarnings("unchecked") T t = (T) value;
            result.add(t);
        }
        return Collections.unmodifiableList(result);
    }
    private static <T> Set<T> immutableSet(Object[] values) {
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value == null) throw new NullPointerException("Set.of does not allow null");
            @SuppressWarnings("unchecked") T t = (T) value;
            if (!result.add(t)) throw new IllegalArgumentException("duplicate element");
        }
        return Collections.unmodifiableSet(result);
    }
    private static <K,V> Map<K,V> immutableMapPairs(Object[] pairs) {
        LinkedHashMap<K,V> result = new LinkedHashMap<>();
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Map factory requires key/value pairs");
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked") K key = (K) pairs[i];
            @SuppressWarnings("unchecked") V value = (V) pairs[i + 1];
            if (key == null || value == null) throw new NullPointerException("Map.of does not allow null");
            if (result.containsKey(key)) throw new IllegalArgumentException("duplicate key");
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
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
