package com.embers.packmod.platform;

import java.nio.file.Path;
import java.util.Optional;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

public final class ModLoader {
    private static final ModLoader INSTANCE = new ModLoader();
    public static ModLoader getInstance() { return INSTANCE; }
    public Path getConfigDir() { return FMLPaths.CONFIGDIR.get(); }
    public boolean isModLoaded(String id) { return ModList.get().isLoaded(id); }
    public Optional<Container> getModContainer(String id) {
        return ModList.get().getModContainerById(id).map(mod -> new Container(new Metadata(new Version(mod.getModInfo().getVersion().toString()))));
    }
    public record Container(Metadata getMetadata) { public Metadata getMetadata() { return getMetadata; } }
    public record Metadata(Version getVersion) { public Version getVersion() { return getVersion; } }
    public record Version(String getFriendlyString) { public String getFriendlyString() { return getFriendlyString; } }
}
