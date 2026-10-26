package org.entcore.common.audit;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Chaînage du journal d'audit : chaque entrée porte {@code seq}, {@code prevHash} et
 * {@code hash = SHA-256(prevHash | canonical(entrée))}. Modifier, insérer ou retirer une entrée
 * casse la chaîne à partir de ce point.
 *
 * La forme canonique trie les clés récursivement : elle ne dépend pas de l'ordre des champs
 * renvoyé par Mongo. Les champs techniques (_id, hash, expireAt) en sont exclus.
 */
public final class AuditChain {

    public static final String GENESIS = "0";
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList("_id", "hash", "expireAt"));

    private AuditChain() {}

    public static String hash(String prevHash, JsonObject entry) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((prevHash + "|" + canonical(entry, true)).getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonical(Object value, boolean root) {
        if (value instanceof JsonObject) {
            final JsonObject o = (JsonObject) value;
            final List<String> keys = new ArrayList<>(o.fieldNames());
            Collections.sort(keys);
            final StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String k : keys) {
                if (root && EXCLUDED.contains(k)) continue;
                if (!first) sb.append(',');
                sb.append(Json.encode(k)).append(':').append(canonical(o.getValue(k), false));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (value instanceof JsonArray) {
            final StringBuilder sb = new StringBuilder("[");
            final JsonArray a = (JsonArray) value;
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(a.getValue(i), false));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Number && !(value instanceof Double || value instanceof Float)) {
            // Mongo peut renvoyer un Long là où un Integer a été écrit : même représentation.
            return Long.toString(((Number) value).longValue());
        }
        return Json.encode(value);
    }
}
