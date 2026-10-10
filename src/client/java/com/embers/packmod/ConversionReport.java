package com.embers.packmod;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Versioned runner result. Archive readability does not verify visual fidelity. */
record ConversionReport(List<Warning> warnings, Changes changes, int completedTasks, List<Repair> repairs) {
    record Warning(String code, String detail) {}
    record Repair(String code, String path, String detail) {}
    record Changes(long added, long modified, long removed) {}
    ConversionReport { warnings = List.copyOf(warnings); repairs = List.copyOf(repairs); }
    ConversionReport(List<Warning> warnings, Changes changes, int completedTasks) { this(warnings, changes, completedTasks, List.of()); }

    static ConversionReport parse(JsonObject data) throws IOException {
        try {
            if (data.get("schema_version").getAsInt() != 1) throw new IOException("Unsupported conversion report version");
            if (!data.get("success").getAsBoolean()) throw new IOException(data.has("error")
                    ? data.get("error").getAsString() : "2-Pyramid conversion failed");
            if (!data.get("archive_validated").getAsBoolean()) throw new IOException("Converted archive was not validated");
            List<Warning> warnings = new ArrayList<>();
            for (var item : data.getAsJsonArray("warnings")) {
                var warning = item.getAsJsonObject();
                warnings.add(new Warning(warning.get("code").getAsString(), warning.get("detail").getAsString()));
            }
            String status = data.get("status").getAsString();
            if (!status.equals(warnings.isEmpty() ? "completed" : "needs_review"))
                throw new IOException("Inconsistent conversion report status");
            int completed = 0;
            for (var item : data.getAsJsonArray("tasks")) {
                var task = item.getAsJsonObject();
                if (!task.get("success").getAsBoolean()) throw new IOException("Failed conversion task: " + task.get("name").getAsString());
                if (!task.has("skipped") || !task.get("skipped").getAsBoolean()) completed++;
            }
            var counts = data.getAsJsonObject("changes");
            Changes changes = new Changes(count(counts, "added"), count(counts, "modified"), count(counts, "removed"));
            List<Repair> repairs = new ArrayList<>();
            if (data.has("repairs")) for (var item : data.getAsJsonArray("repairs")) {
                var repair = item.getAsJsonObject();
                repairs.add(new Repair(repair.get("code").getAsString(), repair.get("path").getAsString(), repair.get("detail").getAsString()));
            }
            return new ConversionReport(warnings, changes, completed, repairs);
        } catch (RuntimeException malformed) { throw new IOException("Invalid conversion report", malformed); }
    }

    private static long count(JsonObject counts, String key) throws IOException {
        long count = counts.get(key).getAsLong();
        if (count < 0 || count > 100_000) throw new IOException("Invalid file change count: " + key);
        return count;
    }

    ConversionReport withWarnings(List<String> codes) {
        List<Warning> merged = new ArrayList<>(warnings);
        for (String code : codes) if (merged.stream().noneMatch(w -> w.code.equals(code))) merged.add(new Warning(code, ""));
        return new ConversionReport(merged, changes, completedTasks, repairs);
    }
}
