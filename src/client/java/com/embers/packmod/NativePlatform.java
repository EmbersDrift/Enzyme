package com.embers.packmod;

import java.io.IOException;
import java.util.Locale;

record NativePlatform(String id, String executable) {
    static NativePlatform current() throws IOException {
        return resolve(System.getProperty("os.name"), System.getProperty("os.arch"));
    }
    static NativePlatform resolve(String os, String arch) throws IOException {
        os = os.toLowerCase(Locale.ROOT); arch = arch.toLowerCase(Locale.ROOT);
        String system = os.contains("windows") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("linux") ? "linux" : null;
        String cpu = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : null;
        if (system == null || cpu == null) throw new IOException("Unsupported runner platform: " + os + "/" + arch);
        return new NativePlatform(system + "-" + cpu, "embers-pyramid" + (system.equals("windows") ? ".exe" : ""));
    }
    String resource() { return "/tools/pyramid/" + id + "/" + executable; }
    boolean bundled() { return NativePlatform.class.getResource(resource()) != null; }
}
