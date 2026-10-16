package com.embers.packmod;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** MOD-side format adapters. The upstream converter and original pack stay untouched. */
final class PackCompatibility {
    record Result(List<ConversionReport.Repair> repairs,List<ConversionReport.Warning> warnings,int added,int modified) {}
    private static final Pattern ITEM=Pattern.compile("assets/([a-z0-9_.-]+)/models/(item/.+)\\.json");
    private static final int MAX_JSON=1_048_576;

    static ConversionReport.Changes changes(Path before,Path after,ConversionControl control)throws IOException {
        var old=snapshot(before,control);var current=snapshot(after,control);
        long added=0,modified=0,removed=0;
        for(var entry:current.entrySet())if(!old.containsKey(entry.getKey()))added++;
            else if(!old.get(entry.getKey()).equals(entry.getValue()))modified++;
        for(String path:old.keySet())if(!current.containsKey(path))removed++;
        return new ConversionReport.Changes(added,modified,removed);
    }
    private static Map<String,String> snapshot(Path archive,ConversionControl control)throws IOException {
        var result=new HashMap<String,String>();byte[] buffer=new byte[65536];
        try(var zip=new ZipFile(archive.toFile())) {
            for(var entry:Collections.list(zip.entries()))if(!entry.isDirectory()) {
                control.check();java.security.MessageDigest hash;
                try{hash=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
                try(var stream=zip.getInputStream(entry)){int n;while((n=stream.read(buffer))!=-1){control.check();hash.update(buffer,0,n);}}
                result.put(entry.getName(),HexFormat.of().formatHex(hash.digest()));
            }
        }
        return result;
    }

    static Result apply(Path archive,PackInspector.Version target,ConversionOptions options,ConversionControl control)throws IOException {
        Map<String,byte[]> additions=new LinkedHashMap<>();
        Map<String,byte[]> replacements=new LinkedHashMap<>();
        var repairs=new ArrayList<ConversionReport.Repair>();var warnings=new ArrayList<ConversionReport.Warning>();
        int optifine=0;
        try(var zip=new ZipFile(archive.toFile())) {
            for(var entry:Collections.list(zip.entries())) {
                control.check();String path=entry.getName();
                if(path.contains("/optifine/")&&path.endsWith(".properties"))optifine++;
                var match=ITEM.matcher(path);
                if(target.major()<46||entry.isDirectory()||!match.matches())continue;
                String output="assets/"+match.group(1)+"/items/"+match.group(2).substring(5)+".json";
                boolean authored=zip.getEntry(output)!=null; // Authored modern definitions take priority.
                if(entry.getSize()>MAX_JSON) { warnings.add(new ConversionReport.Warning("legacy_item_model_review",path+": model JSON exceeds adapter limit"));continue; }
                try(var stream=zip.getInputStream(entry)) {
                    byte[] bytes=stream.readNBytes(MAX_JSON+1);
                    if(bytes.length>MAX_JSON)throw new IllegalArgumentException("model JSON exceeds adapter limit");
                    var data=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                    if(data.has("parent")&&Set.of("builtin/entity","minecraft:builtin/entity").contains(data.get("parent").getAsString())) {
                        // Special item rendering moved into item definitions; this old parent no longer exists.
                        data.remove("parent");replacements.put(path,data.toString().getBytes(StandardCharsets.UTF_8));
                        repairs.add(new ConversionReport.Repair("special_model_parent_removed",path,"Removed obsolete builtin/entity parent; textures and display transforms retained."));
                    }
                    if(authored)continue;
                    if(!data.has("overrides")||data.getAsJsonArray("overrides").size()==0)continue;
                    String base=match.group(1)+":"+match.group(2);
                    var definition=new JsonObject();definition.add("model",migrate(data.getAsJsonArray("overrides"),base,target));
                    byte[] payload=definition.toString().getBytes(StandardCharsets.UTF_8);
                    if(payload.length>MAX_JSON)throw new IllegalArgumentException("generated definition exceeds adapter limit");
                    additions.put(output,payload);
                    repairs.add(new ConversionReport.Repair("legacy_item_model_migrated",output,"Migrated legacy overrides; original model retained."));
                }catch(RuntimeException malformed) {
                    warnings.add(new ConversionReport.Warning("legacy_item_model_review",path+": "+malformed.getMessage()));
                }
            }
            if(options.adaptShaders()) PackShaderCompatibility.adapt(zip,target,replacements,repairs,warnings,control);
            if(target.major()>=12) adaptAtlas(zip,additions,replacements,repairs,control);
            if(zip.size()+additions.size()>100000)throw new IOException("Adapted archive exceeds entry limit");
        }
        if(optifine>0)warnings.add(new ConversionReport.Warning("optifine_features_require_mod",optifine+" OptiFine/CIT property files retained; their effects require a compatible client extension."));
        if(!additions.isEmpty()||!replacements.isEmpty()) {
            Path temp=Files.createTempFile(archive.getParent(),"model-adapter-",".zip");
            try {
                try(var input=new ZipFile(archive.toFile());var output=new ZipOutputStream(Files.newOutputStream(temp))) {
                    for(var entry:Collections.list(input.entries())) {
                        control.check();output.putNextEntry(new ZipEntry(entry.getName()));
                        if(replacements.containsKey(entry.getName()))output.write(replacements.get(entry.getName()));
                        else if(!entry.isDirectory())try(var stream=input.getInputStream(entry)) {
                            byte[] buffer=new byte[65536];int n;
                            while((n=stream.read(buffer))!=-1){control.check();output.write(buffer,0,n);}
                        }
                        output.closeEntry();
                    }
                    for(var file:additions.entrySet()){control.check();output.putNextEntry(new ZipEntry(file.getKey()));output.write(file.getValue());output.closeEntry();}
                }
                // Read every entry before replacing the converter's already-validated archive.
                try(var checked=new ZipFile(temp.toFile())) {
                    byte[] buffer=new byte[65536];long total=0;
                    for(var entry:Collections.list(checked.entries()))if(!entry.isDirectory()) {
                        control.check();var crc=new CRC32();
                        try(var stream=checked.getInputStream(entry)){int n;while((n=stream.read(buffer))!=-1){control.check();crc.update(buffer,0,n);total+=n;if(total>500L*1024*1024)throw new IOException("Adapted archive exceeds size limit");}}
                        if(crc.getValue()!=entry.getCrc())throw new IOException("Adapted archive CRC mismatch: "+entry.getName());
                    }
                }
                control.check();Files.move(temp,archive,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(temp);}
        }
        return new Result(List.copyOf(repairs),List.copyOf(warnings),additions.size(),replacements.size());
    }

    private static void adaptAtlas(ZipFile zip,Map<String,byte[]> additions,Map<String,byte[]> replacements,
            List<ConversionReport.Repair> repairs,ConversionControl control)throws IOException {
        var sprites=new TreeSet<String>();
        for(var entry:Collections.list(zip.entries())) {
            control.check();String name=entry.getName();
            if(!name.matches("assets/[a-z0-9_.-]+/models/.+\\.json")||entry.getSize()>MAX_JSON)continue;
            try(var stream=zip.getInputStream(entry)) {
                var model=JsonParser.parseString(new String(stream.readNBytes(MAX_JSON+1),StandardCharsets.UTF_8)).getAsJsonObject();
                if(!model.has("textures"))continue;
                for(var texture:model.getAsJsonObject("textures").entrySet()) {
                    if(!texture.getValue().isJsonPrimitive())continue;String value=texture.getValue().getAsString();
                    if(value.startsWith("#"))continue;String resource=id(value);String[] parts=resource.split(":",2);
                    if(parts[1].startsWith("block/")||parts[1].startsWith("item/"))continue;
                    if(zip.getEntry("assets/"+parts[0]+"/textures/"+parts[1]+".png")!=null)sprites.add(resource);
                }
            }catch(RuntimeException ignored){/* Model diagnostics belong to the converter/game loader. */}
        }
        if(sprites.isEmpty())return;
        String path="assets/minecraft/atlases/blocks.json";var atlas=new JsonObject();var sources=new JsonArray();
        if(zip.getEntry(path)!=null)try(var stream=zip.getInputStream(zip.getEntry(path))) {
            byte[] bytes=stream.readNBytes(MAX_JSON+1);if(bytes.length>MAX_JSON)return;
            try{atlas=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();sources=atlas.getAsJsonArray("sources");if(sources==null)return;}
            catch(RuntimeException malformed){return;}
        }
        int count=0;
        for(String sprite:sprites) {
            boolean covered=false;
            for(var value:sources)if(value.isJsonObject()) {
                var source=value.getAsJsonObject();String type=source.has("type")?source.get("type").getAsString():"";
                if((type.equals("single")||type.equals("minecraft:single"))&&source.has("resource")&&id(source.get("resource").getAsString()).equals(sprite))covered=true;
            }
            if(covered)continue;
            var single=new JsonObject();single.addProperty("type","minecraft:single");single.addProperty("resource",sprite);sources.add(single);count++;
        }
        if(count==0)return;atlas.add("sources",sources);
        byte[] bytes=atlas.toString().getBytes(StandardCharsets.UTF_8);
        if(zip.getEntry(path)==null)additions.put(path,bytes);else replacements.put(path,bytes);
        repairs.add(new ConversionReport.Repair("custom_sprites_registered",path,"Registered "+count+" model textures outside block/item directories."));
    }

    static JsonObject migrate(JsonArray overrides,String base,PackInspector.Version target) {
        if(overrides.size()>4096)throw new IllegalArgumentException("too many model overrides");
        boolean onlyCustomData=true;
        for(var value:overrides) {
            var predicate=value.getAsJsonObject().getAsJsonObject("predicate");
            onlyCustomData &= predicate.size()==1&&predicate.has("custom_model_data");
        }
        if(onlyCustomData) {
            var entries=new TreeMap<Float,String>();
            for(var value:overrides) {
                var rule=value.getAsJsonObject();float threshold=number(rule.getAsJsonObject("predicate").get("custom_model_data"));
                // Legacy uses the last matching override, including unsorted/duplicate thresholds.
                entries.tailMap(threshold,true).clear();entries.put(threshold,id(rule.get("model").getAsString()));
            }
            var array=new JsonArray();
            for(var entry:entries.entrySet()) {var rule=new JsonObject();rule.addProperty("threshold",entry.getKey());rule.add("model",model(entry.getValue(),target));array.add(rule);}
            var result=typed("range_dispatch");result.addProperty("property","minecraft:custom_model_data");result.addProperty("index",0);result.add("entries",array);result.add("fallback",model(base,target));return result;
        }
        if(overrides.size()>32)throw new IllegalArgumentException("complex predicate chain needs manual migration");
        JsonObject fallback=model(base,target);
        for(var value:overrides) {
            var rule=value.getAsJsonObject();var predicate=rule.getAsJsonObject("predicate");
            if(predicate.size()>8)throw new IllegalArgumentException("too many predicates in one override");
            JsonObject selected=model(id(rule.get("model").getAsString()),target);
            var conditions=new ArrayList<>(predicate.entrySet());
            for(int i=conditions.size()-1;i>=0;i--) {
                var condition=conditions.get(i);selected=predicate(condition.getKey(),number(condition.getValue()),selected,fallback,base);
            }
            fallback=selected;
            if(weight(fallback,new IdentityHashMap<>(),0)>8192)throw new IllegalArgumentException("complex predicate expansion needs manual migration");
        }
        return fallback;
    }

    private static JsonObject predicate(String key,float threshold,JsonObject yes,JsonObject no,String base) {
        String property=switch(key) {
            case "custom_model_data" -> "custom_model_data";
            case "damage" -> "damage";
            case "cooldown" -> "cooldown";
            case "damaged" -> "damaged";
            case "blocking" -> {if(!base.equals("minecraft:item/shield"))throw new IllegalArgumentException("blocking on non-shield");yield "using_item";}
            case "pulling" -> {if(!base.equals("minecraft:item/bow")&&!base.equals("minecraft:item/crossbow"))throw new IllegalArgumentException("pulling on non-bow");yield "using_item";}
            case "cast" -> "fishing_rod/cast";
            case "pull" -> {if(base.equals("minecraft:item/crossbow"))yield "crossbow/pull";if(base.equals("minecraft:item/bow"))yield "use_duration";throw new IllegalArgumentException("pull on non-bow");}
            default -> throw new IllegalArgumentException("unsupported legacy predicate "+key);
        };
        boolean bool=Set.of("damaged","blocking","pulling","cast").contains(key);
        if(bool) {
            if(threshold<=0)return yes;if(threshold>1)return no;
            var node=typed("condition");node.addProperty("property","minecraft:"+property);node.add("on_true",yes);node.add("on_false",no);return node;
        }
        var node=typed("range_dispatch");node.addProperty("property","minecraft:"+property);
        if(key.equals("custom_model_data"))node.addProperty("index",0);
        if(key.equals("damage"))node.addProperty("normalize",true);
        if(property.equals("use_duration"))node.addProperty("scale",0.05);
        var entries=new JsonArray();var entry=new JsonObject();entry.addProperty("threshold",threshold);entry.add("model",yes);entries.add(entry);
        node.add("entries",entries);node.add("fallback",no);return node;
    }

    private static JsonObject model(String reference,PackInspector.Version target) {
        var result=typed("model");result.addProperty("model",id(reference));
        if(reference.equals("minecraft:item/shield")||reference.equals("minecraft:item/shield_blocking")) {
            result=typed("special");result.addProperty("base",reference);result.add("model",typed("shield"));
            if(target.major()>=88) {
                var transform=new JsonObject();
                transform.add("scale",JsonParser.parseString("[1,-1,-1]"));
                transform.add("translation",JsonParser.parseString("[0,0,0]"));
                transform.add("left_rotation",JsonParser.parseString("[0,0,0,1]"));
                transform.add("right_rotation",JsonParser.parseString("[0,0,0,1]"));
                result.add("transformation",transform);
            }
        }
        return result;
    }
    private static JsonObject typed(String type){var value=new JsonObject();value.addProperty("type","minecraft:"+type);return value;}
    private static float number(JsonElement value){float result=value.getAsFloat();if(!Float.isFinite(result))throw new IllegalArgumentException("non-finite model threshold");return result;}
    private static String id(String value){String result=value.contains(":")?value:"minecraft:"+value;if(!result.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||Arrays.asList(result.split("[:/]")).contains(".."))throw new IllegalArgumentException("invalid model identifier");return result;}
    private static int weight(JsonElement value,IdentityHashMap<JsonElement,Integer> memo,int depth) {
        if(depth>128)return 8193;if(memo.containsKey(value))return memo.get(value);int sum=1;
        if(value.isJsonObject())for(var child:value.getAsJsonObject().entrySet()){sum+=weight(child.getValue(),memo,depth+1);if(sum>8192)break;}
        else if(value.isJsonArray())for(var child:value.getAsJsonArray()){sum+=weight(child,memo,depth+1);if(sum>8192)break;}
        memo.put(value,sum);return sum;
    }
}
