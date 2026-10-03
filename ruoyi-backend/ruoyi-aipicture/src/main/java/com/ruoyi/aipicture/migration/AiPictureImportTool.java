package com.ruoyi.aipicture.migration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.aipicture.config.AiPictureProperties;
import com.ruoyi.aipicture.domain.Json;
import com.ruoyi.aipicture.persistence.AiPictureRepository;
import com.ruoyi.aipicture.tool.ToolRegistry;
import com.ruoyi.aipicture.worker.WorkerSignatures;

/** 一次性人工工具，无Bean、启动钩子、HTTP入口、DDL或自动恢复删除。 */
public final class AiPictureImportTool {
    private static final Set<String> TABLES=Set.of("aip_asset","aip_session","aip_session_asset","aip_history","aip_run","aip_batch","aip_batch_item","aip_agent_turn","aip_plan_step","aip_selection");
    private final AiPictureRepository db;private final TransactionTemplate tx;private final ImportVerifier verifier;private final Path storageRoot;private final Path publicProfile;
    public AiPictureImportTool(AiPictureRepository db,TransactionTemplate tx,ImportVerifier verifier,Path storageRoot) {
        this(db,tx,verifier,storageRoot,configuredProfile());
    }
    public AiPictureImportTool(AiPictureRepository db,TransactionTemplate tx,ImportVerifier verifier,Path storageRoot,Path publicProfile) {
        if(!storageRoot.isAbsolute()) throw ImportManifest.invalid("目标私有存储必须显式指定绝对路径");this.storageRoot=storageRoot.normalize();
        if(publicProfile==null||!publicProfile.isAbsolute()||!Files.isDirectory(publicProfile)) throw ImportManifest.invalid("非Spring导入必须显式指定现有若依公开profile绝对目录，不猜配置");
        try {this.publicProfile=publicProfile.toRealPath();if(this.storageRoot.startsWith(publicProfile.normalize())) throw ImportManifest.invalid("导入对象不能放在公开profile目录");guardPrivateRoot();}catch(IOException failure){throw ImportManifest.invalid("私有或公开目录无法完成只读路径核对");}
        this.db=db;this.tx=tx;this.verifier=verifier;
    }
    private static Path configuredProfile(){String profile=com.ruoyi.common.config.RuoYiConfig.getProfile();return profile==null||profile.isBlank()?null:Path.of(profile).toAbsolutePath();}
    private void guardPrivateRoot() throws IOException {
        Path existing=storageRoot;while(existing!=null&&!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) existing=existing.getParent();
        if(existing==null||existing.toRealPath().startsWith(publicProfile)) throw ImportManifest.invalid("私有对象目录实际路径或现有祖先落入公开profile");
    }
    public record Applied(String dataset,String batchId,int inserted,int replayed,Path recoveryFile,List<String> notes) {}
    public ImportVerifier.Prepared verify(ImportManifest manifest) throws IOException {return verifier.verify(manifest);}
    /** 文件先校验并不可变复制，正式行和全部映射在同一事务提交；任何失败都保留恢复侧车。 */
    public Applied apply(ImportManifest manifest) throws IOException {
        var prepared=verifier.verify(manifest);var before=tx.execute(status->preflight(prepared));
        Path journal=manifest.root().resolve("recovery-"+manifest.batchId()+".json");var recovery=recovery(prepared,before,journal);
        writeJournal(journal,recovery,"prepared");
        try {
            guardPrivateRoot();Files.createDirectories(storageRoot);guardPrivateRoot();
            for(var object:prepared.objects()) {
                Path target=target(object.objectKey());var bytes=Files.readAllBytes(ImportVerifier.sourceFile(manifest.root(),manifest.root().relativize(object.file()).toString()));
                if(bytes.length!=object.bytes()||!WorkerSignatures.sha256(bytes).equals(object.sha256())) throw ImportManifest.invalid("校验后源对象变化，拒绝复制或正式写入");
                Files.createDirectories(target.getParent());target=target(object.objectKey());
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||!WorkerSignatures.sha256(Files.readAllBytes(target)).equals(object.sha256())) throw ImportManifest.invalid("目标对象碰撞，拒绝覆盖");}
                else Files.write(target,bytes,StandardOpenOption.CREATE_NEW);
            }
            writeJournal(journal,recovery,"objects_verified");
            int[] counts=tx.execute(status->{
                var existing=preflight(prepared);int inserted=0,replayed=0;
                for(var row:prepared.rows()) {
                    if(existing.contains(rowKey(row))) {replayed++;continue;}
                    if(!row.table().equals("owner")) insert(row);
                    db.execute("INSERT INTO aip_import_map(source_dataset,entity_type,source_id,target_id,user_id,sha256,status,created_at) VALUES(:dataset,:entity,:source,:target,:owner,:hash,'imported',:created)",Json.of("dataset",manifest.dataset(),"entity",row.entity(),"source",row.sourceId(),"target",row.targetId(),"owner",owner(row),"hash",row.fingerprint(),"created",manifest.exportedAt()));inserted++;
                }
                return new int[]{inserted,replayed};
            });
            writeJournal(journal,recovery,"committed");return new Applied(manifest.dataset(),manifest.batchId(),counts[0],counts[1],journal,prepared.notes());
        } catch(IOException|RuntimeException failure) {
            // 提交后的侧车失败也不能重放或删除；下次apply先核现有正式映射和行。
            try {writeJournal(journal,recovery,"interrupted_check_database_before_recovery");}catch(Exception journalFailure){failure.addSuppressed(journalFailure);}throw failure;
        }
    }
    private Set<String> preflight(ImportVerifier.Prepared prepared) {
        for(long user:new HashSet<>(prepared.manifest().owners().values())) if(db.count("SELECT COUNT(*) FROM sys_user WHERE user_id=:owner AND del_flag='0'",Json.of("owner",user))!=1) throw ImportManifest.invalid("owner映射未指向既有未删除若依用户；不创建或绑定账号");
        var existing=new HashSet<String>();
        for(var row:prepared.rows()) {
            var mapping=db.one("SELECT * FROM aip_import_map WHERE source_dataset=:dataset AND entity_type=:entity AND source_id=:source FOR UPDATE",Json.of("dataset",prepared.manifest().dataset(),"entity",row.entity(),"source",row.sourceId()));
            Map<String,Object> actual=row.table().equals("owner")?null:read(row,true);
            if(mapping!=null) {
                if(!row.fingerprint().equals(mapping.get("sha256"))||!row.targetId().equals(mapping.get("target_id"))||Json.number(mapping.get("user_id"))!=owner(row)||!"imported".equals(mapping.get("status"))) throw ImportManifest.invalid("导入映射/owner/hash冲突："+row.entity()+"/"+row.sourceId());
                if(!row.table().equals("owner")&&(actual==null||!matches(row,actual))) throw ImportManifest.invalid("目标行已变化或丢失，拒绝覆盖后续编辑："+row.entity()+"/"+row.sourceId());existing.add(rowKey(row));
            } else if(actual!=null) throw ImportManifest.invalid("目标UUID/关系已存在且不属于本源映射，拒绝接管："+row.entity()+"/"+row.sourceId());
        }
        return existing;
    }
    private Map<String,Object> read(ImportVerifier.Row row,boolean lock) {checkTable(row.table());return db.one("SELECT * FROM "+row.table()+" WHERE "+where(row.keys())+(lock?" FOR UPDATE":""),row.keys());}
    private static String where(Map<String,Object> keys) {return String.join(" AND ",keys.keySet().stream().map(key->{if(!key.matches("[a-z_][a-z0-9_]*")) throw ImportManifest.invalid("内部键不合法");return key+"=:"+key;}).toList());}
    private static void checkTable(String table){if(!TABLES.contains(table)) throw ImportManifest.invalid("未知导入目标表");}
    private void insert(ImportVerifier.Row row) {
        checkTable(row.table());var columns=new ArrayList<>(row.values().keySet());var params=new LinkedHashMap<String,Object>();var arguments=new ArrayList<String>();int index=0;
        for(String column:columns) {if(!column.matches("[a-z_][a-z0-9_]*")) throw ImportManifest.invalid("内部列不合法");String key="value"+index++;Object value=row.values().get(column);params.put(key,value instanceof Map<?,?>||value instanceof List<?>?ImportManifest.write(value):value);arguments.add(":"+key);}
        db.execute("INSERT INTO "+row.table()+"("+String.join(",",columns)+") VALUES("+String.join(",",arguments)+")",params);
    }
    private static boolean matches(ImportVerifier.Row row,Map<String,Object> actual) {
        var projected=new LinkedHashMap<String,Object>();for(var entry:row.values().entrySet()) {Object value=actual.get(entry.getKey());if(entry.getValue() instanceof Boolean&&value instanceof Number n&&Set.of(0,1).contains(n.intValue())) value=n.intValue()==1;projected.put(entry.getKey(),value);}return Json.equivalent(row.values(),projected);
    }
    private static long owner(ImportVerifier.Row row) {return row.owner();}
    private static String rowKey(ImportVerifier.Row row){return row.entity()+"/"+row.sourceId();}
    private Path target(String objectKey) throws IOException {
        Path target=storageRoot.resolve(objectKey).normalize();if(!target.startsWith(storageRoot)||target.equals(storageRoot)||Path.of(objectKey).isAbsolute()) throw ImportManifest.invalid("目标对象路径越界");Path existing=target;while(existing!=null&&!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) existing=existing.getParent();if(existing==null||!existing.toRealPath().startsWith(storageRoot.toRealPath())) throw ImportManifest.invalid("目标对象链接越界");if(Files.isSymbolicLink(target)) throw ImportManifest.invalid("目标对象不能是链接");return target;
    }
    private Map<String,Object> recovery(ImportVerifier.Prepared prepared,Set<String> existing,Path file) throws IOException {
        var entries=new ArrayList<Object>();for(var row:prepared.rows()) entries.add(Json.of("table",row.table(),"entity",row.entity(),"source_id",row.sourceId(),"target_id",row.targetId(),"owner",row.owner(),"keys",row.keys(),"values",row.values(),"fingerprint",row.fingerprint(),"created_by_batch",!existing.contains(rowKey(row))));
        var recovery=Json.of("version","1","source_dataset",prepared.manifest().dataset(),"import_batch_id",prepared.manifest().batchId(),"manifest_sha256",prepared.manifest().sha256(),"storage_root",storageRoot.toString(),"rows",entries,"objects",prepared.objects().stream().map(o->Json.of("object_key",o.objectKey(),"sha256",o.sha256(),"size_bytes",o.bytes())).toList(),"notes",prepared.notes());
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(prepared.manifest().root())) throw ImportManifest.invalid("恢复侧车不是本manifest根内普通文件");var prior=ImportManifest.object(Json.map(Files.readString(file,StandardCharsets.UTF_8)));
            if(!Objects.equals(prior.get("journal_sha256"),ImportManifest.hash(withoutJournalHash(prior)))||!Objects.equals(prior.get("manifest_sha256"),prepared.manifest().sha256())||!Objects.equals(prior.get("source_dataset"),prepared.manifest().dataset())||!Objects.equals(prior.get("import_batch_id"),prepared.manifest().batchId())||!Objects.equals(prior.get("storage_root"),storageRoot.toString())) throw ImportManifest.invalid("恢复清单已变化或属于另一数据包，拒绝覆盖");
            var old=new HashMap<String,Map<String,Object>>();for(Object value:Json.list(prior.get("rows"))) {var entry=ImportManifest.object(value);old.put(entry.get("entity")+"/"+entry.get("source_id"),entry);}
            for(Object value:entries) {var entry=Json.map(value);var original=old.get(entry.get("entity")+"/"+entry.get("source_id"));if(original==null||!Objects.equals(original.get("fingerprint"),entry.get("fingerprint"))) throw ImportManifest.invalid("恢复清单行指纹冲突");if(Json.bool(original.get("created_by_batch"),false)) entry.put("created_by_batch",true);}
        }
        return recovery;
    }
    private static void writeJournal(Path file,Map<String,Object> data,String state) throws IOException {
        if(Files.isSymbolicLink(file)) throw ImportManifest.invalid("恢复侧车不能是链接");data.put("state",state);data.put("journal_sha256",ImportManifest.hash(withoutJournalHash(data)));Path temp=file.resolveSibling(file.getFileName()+".tmp-"+Json.id());
        try {Files.writeString(temp,ImportManifest.write(data),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
    }
    private static Map<String,Object> withoutJournalHash(Map<String,Object> data){var copy=new LinkedHashMap<>(data);copy.remove("journal_sha256");return copy;}
    /** 只读：定位本批次对象、行、后续编辑和新引用。没有DROP、DELETE或文件删除入口。 */
    public Map<String,Object> checkRecovery(Path file) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>64L*1024*1024) throw ImportManifest.invalid("恢复清单必须是有界普通文件");var journal=Json.map(Files.readString(file,StandardCharsets.UTF_8));
        if(!Objects.equals(journal.get("journal_sha256"),ImportManifest.hash(withoutJournalHash(journal)))||!Objects.equals(journal.get("storage_root"),storageRoot.toString())) throw ImportManifest.invalid("恢复清单指纹或目标目录不符");
        var entries=Json.list(journal.get("rows"));var ownKeys=new HashSet<String>();var importedIds=new HashSet<String>();var checks=new ArrayList<Object>();boolean blocked=false;
        for(Object value:entries) {var e=ImportManifest.object(value);if(!Json.bool(e.get("created_by_batch"),false)||"owner".equals(e.get("table"))) continue;var row=new ImportVerifier.Row(Json.string(e.get("table")),Json.string(e.get("entity")),Json.string(e.get("source_id")),ImportManifest.uuid(e.get("target_id")),ImportManifest.integer(e.get("owner"),1,Long.MAX_VALUE),ImportManifest.object(e.get("keys")),ImportManifest.object(e.get("values")));checkTable(row.table());if(!row.fingerprint().equals(e.get("fingerprint"))) throw ImportManifest.invalid("恢复行快照指纹不符");ownKeys.add(identity(row.table(),row.keys()));importedIds.add(row.targetId());var actual=read(row,false);boolean changed=actual!=null&&!matches(row,actual);blocked|=changed;checks.add(Json.of("table",row.table(),"keys",row.keys(),"exists",actual!=null,"unchanged",!changed,"reason",changed?"后续编辑或行身份已变化，禁止删除":"定位信息；不执行删除"));}
        for(Object value:entries) {
            var e=ImportManifest.object(value);if(!Json.bool(e.get("created_by_batch"),false)) continue;
            var mapping=db.one("SELECT * FROM aip_import_map WHERE source_dataset=:dataset AND entity_type=:entity AND source_id=:source",Json.of("dataset",journal.get("source_dataset"),"entity",e.get("entity"),"source",e.get("source_id")));
            boolean actualExists=!"owner".equals(e.get("table"))&&read(new ImportVerifier.Row(Json.string(e.get("table")),Json.string(e.get("entity")),Json.string(e.get("source_id")),ImportManifest.uuid(e.get("target_id")),ImportManifest.integer(e.get("owner"),1,Long.MAX_VALUE),ImportManifest.object(e.get("keys")),ImportManifest.object(e.get("values"))),false)!=null;
            boolean conflict=mapping!=null&&(!Objects.equals(mapping.get("target_id"),e.get("target_id"))||Json.number(mapping.get("user_id"))!=Json.number(e.get("owner"))||!Objects.equals(mapping.get("sha256"),e.get("fingerprint"))||!"imported".equals(mapping.get("status"))||(!"owner".equals(e.get("table"))&&!actualExists));
            if(conflict||(actualExists&&mapping==null)) {blocked=true;checks.add(Json.of("table","aip_import_map","entity",e.get("entity"),"source_id",e.get("source_id"),"unchanged",false,"reason","导入映射未知、变更或与正式行断层，禁止自动恢复"));}
        }
        var references=new ArrayList<Object>();for(String id:importedIds) for(var table:DEPENDENCIES.entrySet()) {
            var spec=table.getValue();var params=Json.of("needle","%"+id+"%","id",id);String predicate=String.join(" OR ",spec.fields().stream().map(column->"CAST("+column+" AS CHAR) LIKE :needle").toList());
            for(var found:db.query("SELECT "+String.join(",",spec.keys())+" FROM "+table.getKey()+" WHERE "+predicate,params)) {
                var keys=new LinkedHashMap<String,Object>();for(String key:spec.keys()) keys.put(key,found.get(key));if(!ownKeys.contains(identity(table.getKey(),keys))) {blocked=true;references.add(Json.of("table",table.getKey(),"keys",keys,"references",id,"reason","新引用或不属于本批次的资料，禁止自动恢复删除"));}
            }
        }
        var objects=new ArrayList<Object>();for(Object value:Json.list(journal.get("objects"))) {var object=ImportManifest.object(value);Path path=target(Json.string(object.get("object_key")));boolean exists=Files.exists(path,LinkOption.NOFOLLOW_LINKS);boolean changed=exists&&(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!WorkerSignatures.sha256(Files.readAllBytes(path)).equals(object.get("sha256")));blocked|=changed;objects.add(Json.of("object_key",object.get("object_key"),"exists",exists,"unchanged",!changed));}
        return Json.of("source_dataset",journal.get("source_dataset"),"import_batch_id",journal.get("import_batch_id"),"state",journal.get("state"),"blocked",blocked,"rows",checks,"new_references",references,"objects",objects,"action","只读检查；恢复须另行冻结新写入并批准步骤，本工具不会删除任何正式数据或对象");
    }
    private record Dependency(List<String> keys,List<String> fields) {}
    private static final Map<String,Dependency> DEPENDENCIES=Map.ofEntries(
        Map.entry("aip_session",new Dependency(List.of("id"),List.of("original_asset_id","current_asset_id","document"))),
        Map.entry("aip_session_asset",new Dependency(List.of("session_id","asset_id"),List.of("session_id","asset_id","latest_document"))),
        Map.entry("aip_history",new Dependency(List.of("id"),List.of("session_id","before_asset_id","after_asset_id","before_document","after_document","params","result"))),
        Map.entry("aip_run",new Dependency(List.of("id"),List.of("session_id","turn_id","batch_id","batch_item_id","params","result","input_snapshot"))),
        Map.entry("aip_agent_turn",new Dependency(List.of("id"),List.of("session_id","mask_asset_id","steps"))),
        Map.entry("aip_plan_step",new Dependency(List.of("turn_id","step_id"),List.of("turn_id","run_id","params"))),
        Map.entry("aip_batch",new Dependency(List.of("id"),List.of("run_id","pipeline"))),
        Map.entry("aip_batch_item",new Dependency(List.of("id"),List.of("batch_id","source_asset_id","run_id","output_ids"))),
        Map.entry("aip_selection",new Dependency(List.of("id"),List.of("session_id","mask_asset_id"))),
        Map.entry("aip_export",new Dependency(List.of("id"),List.of("session_id","run_id","manifest"))),
        Map.entry("aip_evaluation",new Dependency(List.of("id"),List.of("run_id","manifest","metrics"))),
        Map.entry("aip_command",new Dependency(List.of("user_id","command","idempotency_key"),List.of("result")))
    );
    private static String identity(String table,Map<String,Object> keys){return table+":"+Json.write(Json.canonical(keys));}
    /** 默认verify不构造数据源。apply必须人工显式调用并注入既有若依连接，不读取.env。 */
    public static void main(String[] args) throws Exception {
        if(args.length<2||!Set.of("verify","apply","recovery-check").contains(args[0])) throw ImportManifest.invalid("用法：verify manifest.json [现有Python.exe]；apply manifest.json 私有对象绝对目录 现有公开profile绝对目录 [现有Python.exe]；recovery-check recovery.json 私有对象绝对目录 现有公开profile绝对目录");
        String mode=args[0];Path python=mode.equals("verify")&&args.length>2?Path.of(args[2]):mode.equals("apply")&&args.length>4?Path.of(args[4]):null;var verifier=new ImportVerifier(new ToolRegistry(new AiPictureProperties()),ImportVerifier.offlineDecoder(python));
        if(mode.equals("verify")) {var prepared=verifier.verify(ImportManifest.read(Path.of(args[1])));System.out.println(Json.write(Json.of("mode","verify","source_dataset",prepared.manifest().dataset(),"import_batch_id",prepared.manifest().batchId(),"manifest_sha256",prepared.manifest().sha256(),"rows",prepared.rows().stream().collect(java.util.stream.Collectors.groupingBy(ImportVerifier.Row::table,java.util.stream.Collectors.counting())),"objects",prepared.objects().size(),"notes",prepared.notes(),"database_connected",false)));return;}
        if(args.length<4) throw ImportManifest.invalid("写入和恢复检查必须显式给出私有对象目录与现有公开profile目录");String url=System.getenv("RUOYI_DB_URL"),user=System.getenv("RUOYI_DB_USERNAME"),password=System.getenv("RUOYI_DB_PASSWORD");if(url==null||!url.startsWith("jdbc:mysql://")||user==null||password==null) throw ImportManifest.invalid("需要明确注入既有RUOYI_DB连接；不猜配置或读取秘密文件");
        var source=new DriverManagerDataSource(url,user,password);var repo=new AiPictureRepository(new NamedParameterJdbcTemplate(source));var tool=new AiPictureImportTool(repo,new TransactionTemplate(new DataSourceTransactionManager(source)),verifier,Path.of(args[2]),Path.of(args[3]));
        if(mode.equals("recovery-check")) {System.out.println(Json.write(tool.checkRecovery(Path.of(args[1]))));return;}
        var applied=tool.apply(ImportManifest.read(Path.of(args[1])));System.out.println(Json.write(Json.of("source_dataset",applied.dataset(),"import_batch_id",applied.batchId(),"inserted",applied.inserted(),"replayed",applied.replayed(),"recovery_file",applied.recoveryFile().toString(),"notes",applied.notes())));
    }
}
