package com.paicli.eval.benchmark;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;

/** POSIX和Windows ACL均读取真实权限；缺少节点身份、权限或硬链证据时拒绝。 */
public final class SecureFiles {
    public static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.copyOf(PosixFilePermissions.fromString("rwx------"));
    public static final Set<PosixFilePermission> PRIVATE_FILE = Set.copyOf(PosixFilePermissions.fromString("rw-------"));
    private SecureFiles() { }
    public static boolean hasPosix(Path path) { return Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null; }
    public static Set<PosixFilePermission> permissions(Path path,LinkOption... options)throws IOException {
        if(hasPosix(path))return Files.getPosixFilePermissions(path,options);
        AclFileAttributeView acl=acl(path);UserPrincipal owner=acl.getOwner();EnumSet<PosixFilePermission> result=EnumSet.noneOf(PosixFilePermission.class);
        UserPrincipal all=everyone(path);for(AclEntry entry:acl.getAcl())if(entry.type()==AclEntryType.ALLOW&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY)){int scope=entry.principal().equals(owner)?0:entry.principal().equals(all)?2:entry.principal() instanceof GroupPrincipal?1:2;Set<AclEntryPermission> p=entry.permissions();if(p.contains(AclEntryPermission.READ_DATA))result.add(permission(scope,0));if(p.contains(AclEntryPermission.WRITE_DATA)||p.contains(AclEntryPermission.APPEND_DATA)||scope!=0&&p.stream().anyMatch(value->Set.of(AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD).contains(value)))result.add(permission(scope,1));if(p.contains(AclEntryPermission.EXECUTE))result.add(permission(scope,2));}
        for(AclEntry entry:acl.getAcl())if(entry.type()==AclEntryType.DENY&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY)&&entry.principal().equals(owner)){Set<AclEntryPermission> p=entry.permissions();if(p.contains(AclEntryPermission.READ_DATA))result.remove(PosixFilePermission.OWNER_READ);if(p.contains(AclEntryPermission.WRITE_DATA)||p.contains(AclEntryPermission.APPEND_DATA))result.remove(PosixFilePermission.OWNER_WRITE);if(p.contains(AclEntryPermission.EXECUTE))result.remove(PosixFilePermission.OWNER_EXECUTE);}
        return Set.copyOf(result);
    }
    public static Set<PosixFilePermission> getPosixFilePermissions(Path path,LinkOption... options)throws IOException { return permissions(path,options); }
    public static void setPermissions(Path path,Set<PosixFilePermission> permissions)throws IOException {
        rejectLink(path);
        if(hasPosix(path)){Files.setPosixFilePermissions(path,permissions);return;}
        AclFileAttributeView view=acl(path);UserPrincipal owner=current(path);if(!owner.equals(view.getOwner()))throw new IOException("不能修改其他用户所有的资料权限");
        boolean directory=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);List<AclEntry> entries=new ArrayList<>();entries.addAll(entries(owner,permissions,0,directory));
        if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")))entries.addAll(entries(group(path,owner),permissions,1,directory));
        if(permissions.stream().anyMatch(p->p.name().startsWith("OTHERS_")))entries.addAll(entries(everyone(path),permissions,2,directory));
        view.setAcl(entries);
        if(!permissions.equals(permissions(path)))throw new IOException("资料权限写入后未获得相同平台权限证明");
    }
    public static Path setPosixFilePermissions(Path path,Set<PosixFilePermission> permissions)throws IOException { setPermissions(path,permissions);return path; }
    private static List<AclEntry> entries(UserPrincipal principal,Set<PosixFilePermission> mode,int scope,boolean directory){
        if(!directory)return List.of(entry(principal,mode,scope));
        // 目录遍历权只继承到子目录；普通新文件只继承读写，执行必须明确设置。
        AclEntry directories=AclEntry.newBuilder(entry(principal,mode,scope)).setFlags(AclEntryFlag.DIRECTORY_INHERIT).build();
        Set<PosixFilePermission> files=EnumSet.noneOf(PosixFilePermission.class);files.addAll(mode);files.remove(PosixFilePermission.OWNER_EXECUTE);files.remove(PosixFilePermission.GROUP_EXECUTE);files.remove(PosixFilePermission.OTHERS_EXECUTE);
        AclEntry inheritedFiles=AclEntry.newBuilder(entry(principal,files,scope)).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.INHERIT_ONLY).build();return List.of(directories,inheritedFiles);
    }
    private static AclEntry entry(UserPrincipal principal,Set<PosixFilePermission> mode,int scope){
        EnumSet<AclEntryPermission> p=EnumSet.noneOf(AclEntryPermission.class);
        if(scope==0)p.addAll(Set.of(AclEntryPermission.READ_ATTRIBUTES,AclEntryPermission.READ_NAMED_ATTRS,AclEntryPermission.READ_ACL,AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER,AclEntryPermission.SYNCHRONIZE));
        if(mode.contains(permission(scope,0)))p.addAll(Set.of(AclEntryPermission.READ_DATA,AclEntryPermission.READ_ATTRIBUTES,AclEntryPermission.READ_NAMED_ATTRS,AclEntryPermission.READ_ACL,AclEntryPermission.SYNCHRONIZE));
        if(mode.contains(permission(scope,1)))p.addAll(Set.of(AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA,AclEntryPermission.WRITE_ATTRIBUTES,AclEntryPermission.WRITE_NAMED_ATTRS,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD,AclEntryPermission.SYNCHRONIZE));
        if(mode.contains(permission(scope,2)))p.add(AclEntryPermission.EXECUTE);
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal).setPermissions(p).build();
    }
    private static PosixFilePermission permission(int scope,int action){return PosixFilePermission.values()[scope*3+action];}
    private static AclFileAttributeView acl(Path path)throws IOException {rejectLink(path);AclFileAttributeView value=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(value==null)throw new IOException("文件系统不能证明资料ACL权限");return value;}
    private static UserPrincipal current(Path path)throws IOException{return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));}
    private static UserPrincipal group(Path path,UserPrincipal owner)throws IOException{
        var lookup=path.getFileSystem().getUserPrincipalLookupService();for(String name:List.of("BUILTIN\\Users","Users","用户"))try{UserPrincipal value=lookup.lookupPrincipalByGroupName(name);if(!value.equals(owner))return value;}catch(UserPrincipalNotFoundException ignored){}
        for(Path p=path.getParent();p!=null;p=p.getParent())for(AclEntry e:acl(p).getAcl())if(e.principal() instanceof GroupPrincipal&&!e.principal().equals(owner))return e.principal();throw new IOException("文件系统无法确定测试所要求的非所有者组");
    }
    private static UserPrincipal everyone(Path path)throws IOException{var lookup=path.getFileSystem().getUserPrincipalLookupService();for(String name:List.of("Everyone","所有人","S-1-1-0"))try{return lookup.lookupPrincipalByName(name);}catch(UserPrincipalNotFoundException ignored){}throw new IOException("文件系统无法确定Everyone安全主体");}
    private static void rejectLink(Path path)throws IOException{if(Files.isSymbolicLink(path)||!path.toAbsolutePath().normalize().equals(path.toRealPath()))throw new IOException("资料路径包含链接或重解析跳转");}
    public static void requireOwnerOnlyDirectory(Path path)throws IOException {
        rejectLink(path);if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("私有资料根不是目录");if(!permissions(path).equals(PRIVATE_DIRECTORY))throw new IOException("既有资料根须已具备仅所有者读写执行权限");requireCurrentOwner(path);
        if(!hasPosix(path)){var view=acl(path);for(AclEntry e:view.getAcl())if(e.type()==AclEntryType.ALLOW&&!e.principal().equals(view.getOwner())&&!e.permissions().isEmpty())throw new IOException("私有资料根包含非所有者ACL授权");}
    }
    public static void requireSafeParentDirectory(Path path)throws IOException {
        rejectLink(path);requireCurrentOwner(path);var mode=permissions(path);if(mode.contains(PosixFilePermission.GROUP_WRITE)||mode.contains(PosixFilePermission.OTHERS_WRITE))throw new IOException("资料父目录可由非所有者写入");
        if(!hasPosix(path)){var view=acl(path);for(AclEntry e:view.getAcl())if(e.type()==AclEntryType.ALLOW&&!e.principal().equals(view.getOwner())&&e.permissions().stream().anyMatch(p->Set.of(AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD,AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER).contains(p)))throw new IOException("资料父目录具有非所有者可写ACL");}
    }
    private static void requireCurrentOwner(Path path)throws IOException {UserPrincipal expected=hasPosix(path)?Files.getOwner(Path.of(System.getProperty("user.home"))):current(path);if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(expected))throw new IOException("资料节点不属于当前本机用户");}
    public static Path createOwnerOnlyDirectory(Path path)throws IOException{return createDirectory(path,PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));}
    public static Path createFile(Path path,FileAttribute<?>... attrs)throws IOException {try(SeekableByteChannel channel=newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),attrs)){return path;}}
    public static Path createDirectory(Path path,FileAttribute<?>... attrs)throws IOException {Set<PosixFilePermission> selected=mode(attrs,PRIVATE_DIRECTORY);Files.createDirectory(path,initialAttribute(path,selected,true));try{setPermissions(path,selected);return path;}catch(IOException|RuntimeException e){Files.deleteIfExists(path);throw e;}}
    public static Path createDirectories(Path path,FileAttribute<?>... attrs)throws IOException {Path normalized=path.toAbsolutePath().normalize();if(!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS)){Path parent=normalized.getParent();if(parent!=null&&!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))createDirectories(parent,attrs);return createDirectory(normalized,attrs);}rejectLink(normalized);if(!Files.isDirectory(normalized,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(normalized.toString());return path;}
    public static Path createTempDirectory(Path parent,String prefix,FileAttribute<?>... attrs)throws IOException {Set<PosixFilePermission> selected=mode(attrs,PRIVATE_DIRECTORY);Path path=Files.createTempDirectory(parent,prefix,initialAttribute(parent.resolve(".pending"),selected,true));setPermissions(path,selected);return path;}
    public static Path createTempDirectory(String prefix,FileAttribute<?>... attrs)throws IOException {return createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),prefix,attrs);}
    public static Path createTempFile(Path parent,String prefix,String suffix,FileAttribute<?>... attrs)throws IOException {Set<PosixFilePermission> selected=mode(attrs,PRIVATE_FILE);Path path=Files.createTempFile(parent,prefix,suffix,initialAttribute(parent.resolve(".pending"),selected,false));setPermissions(path,selected);return path;}
    public static Path createTempFile(String prefix,String suffix,FileAttribute<?>... attrs)throws IOException {return createTempFile(Path.of(System.getProperty("java.io.tmpdir")),prefix,suffix,attrs);}
    public static SeekableByteChannel newByteChannel(Path path,Set<? extends OpenOption> options,FileAttribute<?>... attrs)throws IOException {Set<PosixFilePermission> selected=mode(attrs,PRIVATE_FILE);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))rejectLink(path);SeekableByteChannel channel=Files.newByteChannel(path,options,initialAttribute(path,selected,false));try{setPermissions(path,selected);return channel;}catch(IOException|RuntimeException e){channel.close();throw e;}}
    private static FileAttribute<?> initialAttribute(Path path,Set<PosixFilePermission> mode,boolean directory)throws IOException {
        Path parent=path.toAbsolutePath().normalize().getParent();if(parent==null)throw new IOException("资料节点没有有效父目录");rejectLink(parent);if(hasPosix(parent))return PosixFilePermissions.asFileAttribute(mode);if(Files.getFileAttributeView(parent,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)==null)throw new IOException("文件系统不能原子创建私有资料节点");
        List<AclEntry> entries=new ArrayList<>();entries.addAll(entries(current(parent),mode,0,directory));if(mode.stream().anyMatch(p->p.name().startsWith("GROUP_")))entries.addAll(entries(group(parent,current(parent)),mode,1,directory));if(mode.stream().anyMatch(p->p.name().startsWith("OTHERS_")))entries.addAll(entries(everyone(parent),mode,2,directory));List<AclEntry> value=List.copyOf(entries);return new FileAttribute<List<AclEntry>>(){public String name(){return "acl:acl";}public List<AclEntry> value(){return value;}};
    }
    @SuppressWarnings("unchecked") private static Set<PosixFilePermission> mode(FileAttribute<?>[] attrs,Set<PosixFilePermission> fallback)throws IOException {Set<PosixFilePermission> result=fallback;for(FileAttribute<?> attr:attrs){if(!"posix:permissions".equals(attr.name())||!(attr.value() instanceof Set<?>))throw new IOException("不支持的资料创建权限属性");result=Set.copyOf((Set<PosixFilePermission>)attr.value());}return result;}
    public static Object identity(Path path,BasicFileAttributes attrs)throws IOException {if(attrs.fileKey()!=null)return attrs.fileKey();if(hasPosix(path))throw new IOException("资料节点缺少文件身份");String output=windowsMetadata("file","queryfileid",path);var match=java.util.regex.Pattern.compile("(?i)0x[0-9a-f]{16,128}").matcher(output);if(!match.find())throw new IOException("文件系统未返回真实文件ID");return path.toRealPath().getRoot()+":"+match.group().toLowerCase(Locale.ROOT);}
    public static long linkCount(Path path)throws IOException {if(hasPosix(path)){Object value=Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS);if(value instanceof Number n&&n.longValue()>0)return n.longValue();throw new IOException("资料文件缺少硬链计数");}String output=windowsMetadata("hardlink","list",path);long count=output.lines().map(String::strip).filter(line->line.startsWith("\\")).count();if(count==0)throw new IOException("文件系统未返回真实硬链列表");return count;}
    public static void requireRegularNode(Path path,BasicFileAttributes attrs,boolean directory,Set<Object> seen)throws IOException {if(attrs==null||attrs.isSymbolicLink()||(directory?!attrs.isDirectory():!attrs.isRegularFile()))throw new IOException("资料包含链接或特殊节点");rejectLink(path);Object identity=identity(path,attrs);if(!seen.add(identity))throw new IOException("资料节点身份被复用");if(!directory&&linkCount(path)!=1)throw new IOException("资料包含硬链接");}
    private static String windowsMetadata(String category,String action,Path path)throws IOException {
        rejectLink(path);if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IOException("当前平台没有可验证的文件身份适配");String system=System.getenv("SystemRoot");if(system==null||system.isBlank())throw new IOException("Windows系统目录不可用");Path executable=Path.of(system,"System32","fsutil.exe").toAbsolutePath().normalize();if(!Files.isRegularFile(executable)||Files.isSymbolicLink(executable))throw new IOException("可信Windows文件元数据工具不可用");
        Process process=new ProcessBuilder(executable.toString(),category,action,path.toAbsolutePath().normalize().toString()).redirectErrorStream(true).start();FutureTask<byte[]> output=new FutureTask<>(()->{try(InputStream input=process.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>128*1024){process.destroyForcibly();throw new IOException("文件元数据输出超过安全上限");}bytes.write(buffer,0,count);}return bytes.toByteArray();}});Thread reader=new Thread(output,"paicli-file-metadata");reader.setDaemon(true);reader.start();
        try{if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("文件元数据读取超时");}byte[] bytes=output.get(1,TimeUnit.SECONDS);if(process.exitValue()!=0)throw new IOException("文件系统未提供可靠的文件元数据");return new String(bytes,java.nio.charset.Charset.defaultCharset());}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException("文件元数据读取被中断",e);}catch(ExecutionException|TimeoutException e){process.destroyForcibly();throw new IOException("文件元数据读取失败",e);}finally{process.destroy();}
    }
}
