package com.embers.packmod;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Independent semantic checks for last-matching predicates and safe adapter boundaries. */
final class CompatibilityAdapterCheck {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void run(Path work)throws Exception {
        var target=new PackInspector.Version(88,0);
        var overrides=JsonParser.parseString("[{\"predicate\":{\"custom_model_data\":100},\"model\":\"item/a\"},{\"predicate\":{\"custom_model_data\":50},\"model\":\"item/b\"},{\"predicate\":{\"custom_model_data\":150},\"model\":\"item/c\"},{\"predicate\":{\"custom_model_data\":150},\"model\":\"item/d\"}]").getAsJsonArray();
        var migrated=PackCompatibility.migrate(overrides,"minecraft:item/test",target);
        for(float number:new float[]{0,49,50,99,100,149,150,200}) {
            String expected="minecraft:item/test";
            for(var rule:overrides)if(number>=rule.getAsJsonObject().getAsJsonObject("predicate").get("custom_model_data").getAsFloat())expected="minecraft:"+rule.getAsJsonObject().get("model").getAsString();
            check(evaluate(migrated,Map.of("minecraft:custom_model_data",number)).equals(expected),"Last matching CMD override at "+number);
        }
        var complex=JsonParser.parseString("[{\"predicate\":{\"damaged\":1,\"damage\":0.95},\"model\":\"item/worn\"}]").getAsJsonArray();
        var wear=PackCompatibility.migrate(complex,"minecraft:item/golden_axe",target);
        check(evaluate(wear,Map.of("minecraft:damaged",1f,"minecraft:damage",.96f)).equals("minecraft:item/worn"),"AND predicates match");
        check(evaluate(wear,Map.of("minecraft:damaged",0f,"minecraft:damage",.96f)).equals("minecraft:item/golden_axe"),"AND false branch retains base");
        check(evaluate(wear,Map.of("minecraft:damaged",1f,"minecraft:damage",.94f)).equals("minecraft:item/golden_axe"),"Normalized damage threshold");
        boolean rejected=false;try{PackCompatibility.migrate(JsonParser.parseString("[{\"predicate\":{\"unknown\":1},\"model\":\"item/a\"}]").getAsJsonArray(),"minecraft:item/test",target);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Unknown predicates require review rather than silently losing conditions");
        String vertex="#moj_import <minecraft:include/globals.glsl>\n#version 150\nuniform mat4 ProjMat;\nuniform mat4 ModelViewMat;\nuniform float LineWidth;\nin vec3 Position;\nvoid main() { gl_Position = ProjMat * ModelViewMat * vec4(Position,1); }";
        String shader=PackShaderCompatibility.adaptSource(vertex,"assets/minecraft/shaders/core/rendertype_lines.vsh",88);
        check(shader.startsWith("#version 330\n")&&!shader.contains(":include/")&&shader.contains("in float LineWidth;")&&shader.contains("fog_spherical_distance(Position)"),"Modern shader import/version/width/fog");
        check(PackShaderCompatibility.adaptSource(vertex,"test.vsh",32).equals(vertex),"Old shader interface unchanged");
        Path archive=work.resolve("adapter-fixture.zip");
        String authored="{\"model\":{\"type\":\"minecraft:empty\"}}";
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))) {
            var files=new LinkedHashMap<String,String>();
            files.put("assets/minecraft/models/item/test.json","{\"overrides\":"+overrides+"}");
            files.put("assets/minecraft/items/test.json",authored);
            files.put("assets/minecraft/models/item/unknown.json","{\"overrides\":[{\"predicate\":{\"unknown\":1},\"model\":\"item/a\"}]}");
            files.put("assets/minecraft/models/item/shield_blocking.json","{\"parent\":\"builtin/entity\",\"display\":{\"gui\":{\"scale\":[0.7,0.7,0.7]}},\"textures\":{\"particle\":\"block/oak_planks\"}}");
            files.put("assets/minecraft/models/block/custom.json","{\"textures\":{\"all\":\"test:custom/stone\"}}");
            files.put("assets/test/textures/custom/stone.png","fixture");
            files.put("assets/minecraft/atlases/blocks.json","{\"sources\":[{\"type\":\"minecraft:single\",\"resource\":\"test:existing\"}]}");
            for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        var result=PackCompatibility.apply(archive,target,ConversionOptions.defaults(),new ConversionControl());
        check(result.warnings().stream().anyMatch(w->w.code().equals("legacy_item_model_review")),"Unsupported legacy item gets durable warning");
        try(var zip=new ZipFile(archive.toFile())) {
            check(new String(zip.getInputStream(zip.getEntry("assets/minecraft/items/test.json")).readAllBytes(),StandardCharsets.UTF_8).equals(authored),"Authored modern definition wins");
            var atlas=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("assets/minecraft/atlases/blocks.json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            check(atlas.getAsJsonArray("sources").size()==2,"Atlas extends authored sources");
            var shield=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("assets/minecraft/models/item/shield_blocking.json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            check(!shield.has("parent")&&shield.has("display")&&shield.has("textures"),"Special renderer parent removed while custom display/texture preserved");
        }
        Path before=work.resolve("counts-before.zip"),after=work.resolve("counts-after.zip");
        for(Path path:List.of(before,after))try(var zip=new ZipOutputStream(Files.newOutputStream(path))) {
            var files=path.equals(before)?Map.of("same","keep","changed","before","removed","gone"):Map.of("same","keep","changed","after","added","new");
            for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        check(PackCompatibility.changes(before,after,new ConversionControl()).equals(new ConversionReport.Changes(1,1,1)),"Final changes count each path once across all processing stages");
        System.out.println("COMPATIBILITY ADAPTER PASS predicate semantics, shader interface, authored definitions, warnings and atlas merge");
    }
    private static String evaluate(JsonObject model,Map<String,Float> values) {
        String type=model.get("type").getAsString();
        if(type.equals("minecraft:model"))return model.get("model").getAsString();
        float value=values.getOrDefault(model.get("property").getAsString(),0f);
        if(type.equals("minecraft:condition"))return evaluate(model.getAsJsonObject(value>0?"on_true":"on_false"),values);
        JsonObject selected=model.getAsJsonObject("fallback");
        for(var entry:model.getAsJsonArray("entries"))if(value>=entry.getAsJsonObject().get("threshold").getAsFloat())selected=entry.getAsJsonObject().getAsJsonObject("model");
        return evaluate(selected,values);
    }
}
