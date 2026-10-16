package com.embers.packmod;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Escape literal JSON string controls in the staged metadata only. */
final class PackMetadataStaging {
    record Normalized(byte[] bytes, int escapedControls) {}

    static Normalized normalize(byte[] bytes) throws IOException {
        // Do not silently replace malformed UTF-8 with replacement characters.
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        StringBuilder result = new StringBuilder(text.length());
        boolean string = false, escaped = false;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string && c < 0x20) {
                if (escaped) throw new IOException("Invalid escape before control character in pack.mcmeta");
                result.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                count++;
            } else {
                result.append(c);
                if (escaped) escaped = false;
                else if (string && c == '\\') escaped = true;
                else if (c == '"') string = !string;
            }
        }
        if (count == 0) return new Normalized(bytes, 0);
        String normalized = result.toString();
        try {
            // Gson accepts raw controls like Minecraft. Their decoded values must
            // remain identical after escaping; retain all unrelated bytes/fields.
            String originalJson = text.startsWith("\uFEFF") ? text.substring(1) : text;
            String normalizedJson = normalized.startsWith("\uFEFF") ? normalized.substring(1) : normalized;
            if (!JsonParser.parseString(originalJson).equals(JsonParser.parseString(normalizedJson)))
                throw new IOException("Metadata normalization changed JSON values");
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid resource pack metadata: " + malformed.getMessage(), malformed);
        }
        return new Normalized(normalized.getBytes(StandardCharsets.UTF_8), count);
    }
}
