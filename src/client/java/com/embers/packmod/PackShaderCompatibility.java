package com.embers.packmod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Bridges retained legacy GLSL to the game's uniform blocks without replacing authored effects. */
final class PackShaderCompatibility {
    private static final int LIMIT=1_048_576;
    static void adapt(ZipFile zip,PackInspector.Version target,Map<String,byte[]> replacements,
            List<ConversionReport.Repair> repairs,List<ConversionReport.Warning> warnings,ConversionControl control)throws IOException {
        for(var entry:Collections.list(zip.entries())) {
            control.check();String path=entry.getName();
            if(!path.matches("assets/[a-z0-9_.-]+/shaders/core/.+\\.[vf]sh"))continue;
            if(entry.getSize()>LIMIT){warnings.add(new ConversionReport.Warning("shader_adapter_review",path+": shader exceeds adapter limit"));continue;}
            String original;
            try(var stream=zip.getInputStream(entry)){byte[] bytes=stream.readNBytes(LIMIT+1);if(bytes.length>LIMIT)continue;original=new String(bytes,StandardCharsets.UTF_8);}
            String adapted=adaptSource(original,path,target.major());
            if(!adapted.equals(original)) {
                replacements.put(path,adapted.getBytes(StandardCharsets.UTF_8));
                repairs.add(new ConversionReport.Repair("shader_interface_migrated",path,"Adapted imports, uniforms and fog varyings to the target shader interface."));
            }
        }
    }

    static String adaptSource(String original,String path,int format) {
        if(format<56)return original;
        String text=original.replaceAll("<minecraft:include/([^>]+)>","<minecraft:$1>");
        var imports=new LinkedHashSet<String>();
        String[][] uniforms={
            {"projection","ProjMat"},
            {"dynamictransforms","ModelViewMat","ColorModulator","ModelOffset","TextureMat"},
            {"globals","ScreenSize","GameTime","GlintAlpha","MenuBlurRadius"},
            {"fog","FogColor","FogStart","FogEnd","FogShape"}
        };
        boolean legacyFog=Pattern.compile("\\blinear_fog\\s*\\(").matcher(text).find();
        for(String[] group:uniforms)for(int i=1;i<group.length;i++) {
            var declaration=Pattern.compile("(?m)^[ \\t]*uniform\\s+\\w+\\s+"+group[i]+"\\s*;[ \\t]*");
            var match=declaration.matcher(text);
            if(match.find()){imports.add(group[0]);text=match.replaceAll("");}
        }
        var width=Pattern.compile("(?m)^[ \\t]*uniform\\s+float\\s+LineWidth\\s*;[ \\t]*");
        if(width.matcher(text).find()) {
            if(format>=75)text=width.matcher(text).replaceAll("in float LineWidth;");
            else{imports.add("dynamictransforms");text=width.matcher(text).replaceAll("");}
        }
        if(legacyFog) {
            // The old environmental/range fog was one distance; modern fog needs both distances.
            text=text.replaceAll("\\blinear_fog\\s*\\(\\s*([A-Za-z_][A-Za-z_0-9]*)\\s*,\\s*vertexDistance\\s*,\\s*FogStart\\s*,\\s*FogEnd\\s*,\\s*FogColor\\s*\\)",
                "apply_fog($1, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor)");
            if(text.contains("apply_fog(")) {
                imports.add("fog");
                text=text.replaceAll("(?m)^\\s*in\\s+float\\s+vertexDistance\\s*;", "in float sphericalVertexDistance;\nin float cylindricalVertexDistance;");
            }
        }
        // Legacy line vertex shaders can lack even the old fog varying. Match the modern fragment interface.
        if(path.endsWith("/rendertype_lines.vsh")&&!text.contains("sphericalVertexDistance")) {
            imports.add("fog");
            text=text.replaceAll("(?m)^\\s*out\\s+float\\s+vertexDistance\\s*;", "");
            text=text.replaceFirst("void\\s+main\\s*\\(\\s*\\)\\s*\\{",
                "out float sphericalVertexDistance;\nout float cylindricalVertexDistance;\nvoid main() {\n sphericalVertexDistance = fog_spherical_distance(Position);\n cylindricalVertexDistance = fog_cylindrical_distance(Position);");
        }
        for(String name:imports)if(!text.contains("<minecraft:"+name+".glsl>"))text="#moj_import <minecraft:"+name+".glsl>\n"+text;
        // Imports injected by the upstream adapter may precede #version, which GLSL rejects.
        if(!text.equals(original)) {
            text=text.replaceAll("(?m)^\\s*#version[^\\r\\n]*(?:\\r?\\n|$)","");
            text="#version "+(format>=69?330:150)+"\n"+text.stripLeading();
        }
        return text;
    }
}
