package com.embers.packmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import java.nio.charset.StandardCharsets;

/** Resolve the running game's resource format; refuse a mismatched build declaration. */
final class ConversionTarget {
    static PackInspector.Version current() {
        var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        return new PackInspector.Version(format.major(), format.minor());
    }

    static JsonObject verify(PackInspector.Version target) {
        try (var stream = ConversionTarget.class.getResourceAsStream("/assets/packmod/conversion-target.json")) {
            if (stream == null) throw new IllegalStateException("Conversion target declaration is missing");
            var declared = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String game = SharedConstants.getCurrentVersion().name();
            var actual = current();
            if (!game.equals(declared.get("minecraft").getAsString())
                    || actual.major() != declared.get("major").getAsInt()
                    || actual.minor() != declared.get("minor").getAsInt() || !target.equals(actual)) {
                throw new IllegalStateException("Conversion target mismatch: running " + game + " / " + actual
                        + ", declared " + declared + ", selected " + target);
            }
            var result = declared.deepCopy();
            result.addProperty("running_minecraft", game);
            result.addProperty("running_format", actual.toString());
            result.addProperty("selected_target", target.toString());
            result.addProperty("passed", true);
            return result;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot validate conversion target", error);
        }
    }
}
