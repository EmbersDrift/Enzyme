package com.embers.packmod;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;

/** Checks the same frozen native engine against each version's real target format. */
public final class VersionConversionCheck {
    private static void check(boolean ok,String detail){if(!ok)throw new AssertionError(detail);}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).resolve("run-"+System.nanoTime()).toAbsolutePath();Files.createDirectories(root);
        CompatibilityAdapterCheck.run(root);
        Path source=root.resolve("legacy.zip"),packs=root.resolve("packs"),tools=root.resolve("tools");Files.createDirectories(packs);
        try(var z=new ZipOutputStream(Files.newOutputStream(source))){
            for(var file:Map.of("pack.mcmeta","{\"pack\":{\"pack_format\":1,\"description\":\"Compatibility check\"}}","assets/test/marker.txt","preserve me").entrySet()){
                z.putNextEntry(new ZipEntry(file.getKey()));z.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));z.closeEntry();
            }
        }
        byte[] original=Files.readAllBytes(source);
        var target=new PackInspector.Version(Integer.parseInt(args[1]),0);
        MetadataStagingCheck.run(root,packs,tools,target);
        var result=PyramidConverter.convertWithReport(PackInspector.inspect(source),target,packs,tools,ignored->{});
        check(PackInspector.inspect(result.output()).supports(target),"Native output matches Minecraft "+args[2]);
        check(Arrays.equals(original,Files.readAllBytes(source)),"Source bytes preserved");
        try(var z=new ZipFile(result.output().toFile())){
            check(new String(z.getInputStream(z.getEntry("assets/test/marker.txt")).readAllBytes()).equals("preserve me"),"Custom asset preserved");
        }
        JsonObject report=JsonParser.parseString(Files.readString(result.reportFile())).getAsJsonObject();
        check(report.get("archive_validated").getAsBoolean()&&report.get("metadata_validated").getAsBoolean(),"ZIP and target metadata validated");
        check(!PackJobHistory.load(tools.resolve("jobs")).isEmpty(),"History is durable");
        Path archived=PackConversionFiles.archive(source,PackConversionFiles.signature(source),root);
        check(Arrays.equals(original,Files.readAllBytes(archived)),"Archive preserves source");
        JsonObject env=new JsonObject();env.addProperty("minecraft_version",args[2]);
        Path diagnostic=PackDiagnostics.export(result.reportFile().getParent(),root.resolve("diagnostics"),env);
        try(var z=new ZipFile(diagnostic.toFile())){
            check(z.getEntry("result.json")!=null&&z.getEntry("bundle-manifest.json")!=null,"Diagnostics contains version evidence");
        }
        Path newer=root.resolve("newer.zip");
        try(var z=new ZipOutputStream(Files.newOutputStream(newer))){
            z.putNextEntry(new ZipEntry("pack.mcmeta"));z.write("{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0],\"description\":\"Downgrade\"}}".getBytes());z.closeEntry();
            z.putNextEntry(new ZipEntry("assets/test/marker.txt"));z.write("preserve downgrade".getBytes());z.closeEntry();
        }
        byte[] before=Files.readAllBytes(newer);
        var down=PyramidConverter.convertWithReport(PackInspector.inspect(newer),target,packs,tools,ignored->{});
        check(PackInspector.inspect(down.output()).supports(target),"Downgrade metadata matches target");
        check(Arrays.equals(before,Files.readAllBytes(newer)),"Downgrade preserves original");
        try(var z=new ZipFile(down.output().toFile())){check(new String(z.getInputStream(z.getEntry("assets/test/marker.txt")).readAllBytes()).equals("preserve downgrade"),"Downgrade preserves custom assets");}
        System.out.println("COMPATIBILITY PASS Minecraft "+args[2]+" format "+target+" upgrade/downgrade/source/asset/CRC/history/archive/diagnostics");
    }
}
