package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.*;

/** 完整预览差异保留在正式产物，流式提取恢复授权所需的六字段文件证据。 */
public final class PcSnapshotArtifact {
    private static final Set<String> FILE_FIELDS=Set.of("path","status","currentHash","targetHash","currentMode","targetMode");
    private final PcStore store;private final PcArtifactService artifacts;
    public PcSnapshotArtifact(PcStore store,PcArtifactService artifacts){this.store=store;this.artifacts=artifacts;}
    public Map<String,Object> proof(PcRecord task,String callId,Map<String,Object> projection){
        long id=PcJson.number(projection,"previewArtifactId");Map<String,Object> metadata=artifacts.runnerOutputMetadata(id,task.getDirectoryContextId(),task.getOwnerId(),task.getSessionId());
        if(!String.valueOf(task.getId()).equals(metadata.get("parentId"))||!callId.equals(metadata.get("toolCallId"))||!Objects.equals(metadata.get("sha256"),projection.get("previewArtifactSha256"))||PcJson.number(metadata,"size")!=PcJson.number(projection,"previewArtifactSize"))throw PcException.deny("预览产物不属于当前许可调用或完整SHA/长度不匹配");
        var content=artifacts.content(task.getOwnerId(),id);ObjectMapper mapper=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(128L*1024*1024).maxStringLength(128*1024*1024).build()).build()).rebuild().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();Map<String,Object> body=new LinkedHashMap<>();
        try(var input=new PcCatalogueArtifact.LimitedInput(Files.newInputStream(content.file()));var parser=mapper.createParser(input)){
            require(parser.nextToken(),JsonToken.START_OBJECT);Set<String> names=new HashSet<>();while(parser.nextToken()!=JsonToken.END_OBJECT){String field=property(parser,names);parser.nextToken();if(field.equals("restorePreview"))body.put(field,readProof(parser,mapper,input));else if(Set.of("currentFingerprint","commitId","snapshotMetadataId","snapshotVersion").contains(field)){if(!parser.currentToken().isScalarValue())throw PcException.invalid("预览产物目标字段非法");input.limit(parser.currentTokenLocation().getByteOffset()+17408);String value=parser.getValueAsString();input.limit(128L*1024*1024);if(value==null||value.length()>256)throw PcException.invalid("预览产物目标字段超限");body.put(field,value);}else parser.skipChildren();}if(parser.nextToken()!=null)throw PcException.invalid("预览产物包含尾随内容");if(input.consumed!=content.size()||!input.sha256().equals(content.sha256()))throw PcException.conflict("预览产物流式读取期间SHA或长度改变");
        }catch(PcException error){throw error;}catch(Exception error){throw PcException.invalid("预览产物无法读取完整JSON证据");}
        // 第二次核文件内容，防止流式读取中替换或修改正文。
        var checked=artifacts.content(task.getOwnerId(),id);if(!checked.sha256().equals(content.sha256())||checked.size()!=content.size())throw PcException.conflict("预览产物读取期间改变");
        for(String field:List.of("currentFingerprint","commitId","snapshotMetadataId","snapshotVersion"))if(!Objects.toString(body.get(field),"").equals(Objects.toString(projection.get(field),"")))throw PcException.conflict("预览产物目标或指纹与回执投影不同");
        Map<String,Object> original=PcJson.object(body,"restorePreview"),projected=PcJson.object(projection,"restorePreview");if(!store.json.hash(original).equals(store.json.hash(withoutDiff(projected))))throw PcException.conflict("预览六字段证据与完整差异产物不同");
        return projected;
    }
    private Map<String,Object> readProof(JsonParser parser,ObjectMapper mapper,PcCatalogueArtifact.LimitedInput input)throws Exception{
        require(parser.currentToken(),JsonToken.START_OBJECT);Map<String,Object> proof=new LinkedHashMap<>();Set<String> names=new HashSet<>();while(parser.nextToken()!=JsonToken.END_OBJECT){String field=property(parser,names);parser.nextToken();if(field.equals("files")){require(parser.currentToken(),JsonToken.START_ARRAY);List<Map<String,Object>> files=new ArrayList<>();while(parser.nextToken()!=JsonToken.END_ARRAY){if(files.size()>=10000)throw PcException.invalid("预览文件超过10000条，不能批准部分文件");require(parser.currentToken(),JsonToken.START_OBJECT);Map<String,Object> file=new LinkedHashMap<>();Set<String> fields=new HashSet<>();while(parser.nextToken()!=JsonToken.END_OBJECT){String name=property(parser,fields);parser.nextToken();if(FILE_FIELDS.contains(name)){if(!parser.currentToken().isScalarValue())throw PcException.invalid("预览文件六字段须为标量");input.limit(parser.currentTokenLocation().getByteOffset()+81920);file.put(name,mapper.readValue(parser,Object.class));input.limit(128L*1024*1024);}else parser.skipChildren();}files.add(file);}proof.put(field,files);}else if(!field.equals("diff")){input.limit(parser.currentTokenLocation().getByteOffset()+16L*1024*1024+16384);proof.put(field,mapper.readValue(parser,Object.class));input.limit(128L*1024*1024);if(store.json.write(proof).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16L*1024*1024)throw PcException.invalid("完整恢复证据超过16MiB，未批准部分文件");}else parser.skipChildren();}if(store.json.write(proof).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16L*1024*1024)throw PcException.invalid("完整恢复证据超过16MiB，未批准部分文件");return proof;
    }
    private static Map<String,Object> withoutDiff(Map<String,Object> proof){Map<String,Object> result=new LinkedHashMap<>(proof);result.remove("diff");if(proof.get("files") instanceof List<?> files){List<Map<String,Object>> items=new ArrayList<>();for(Object value:files){if(!(value instanceof Map<?,?> file))throw PcException.invalid("预览文件对象非法");Map<String,Object> item=new LinkedHashMap<>();for(String field:FILE_FIELDS)if(file.containsKey(field))item.put(field,file.get(field));items.add(item);}result.put("files",items);}return result;}
    private static String property(JsonParser parser,Set<String> names){require(parser.currentToken(),JsonToken.PROPERTY_NAME);String name=parser.currentName();if(!names.add(name))throw PcException.invalid("预览产物字段重复");return name;}
    private static void require(JsonToken actual,JsonToken expected){if(actual!=expected)throw PcException.invalid("预览产物JSON结构非法");}
}
