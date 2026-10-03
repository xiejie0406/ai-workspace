package com.paicli.runner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runner本机派生状态的私有目录边界，不修改既有宽权限目录或共享TEMP。 */
public final class RunnerPrivateDirectory {
    private static final String CURRENT_USER = System.getProperty("user.name");
    private static final Map<FileSystem,List<UserPrincipal>> AUTHORITIES = new ConcurrentHashMap<>();
    private static final Set<AclEntryPermission> MUTATIONS = EnumSet.of(AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES,AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER);

    private RunnerPrivateDirectory() { }

    public static Path prepare(Path requested) throws IOException { return createDirectories(requested); }

    /** 只对新节点设置初始权限；已有节点及完整父链必须已满足安全条件。 */
    public static Path createDirectories(Path requested) throws IOException {
        Path target=normalized(requested),existing=target;
        Deque<Path> missing=new ArrayDeque<>();
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing);existing=existing.getParent();
            if(existing==null)throw new IOException("Runner私有目录没有可验证父目录");
        }
        requireSafeParents(existing);
        for(Path directory:missing) {
            requireSafeParents(directory.getParent());
            try { Files.createDirectory(directory,ownerAttribute(directory.getParent(),true)); }
            catch(FileAlreadyExistsException concurrent) { requireDirectory(directory);continue; }
            requireNode(directory,true);requireSafeParents(directory.getParent());
        }
        requireDirectory(target);return target;
    }

    public static void requireDirectory(Path path) throws IOException {
        Path directory=normalized(path);requireSafeParents(directory.getParent());requireNode(directory,true);
    }

    public static void requireFile(Path path) throws IOException { requireRegularFile(path); }

    public static void requireRegularFile(Path path) throws IOException {
        Path file=normalized(path);requireSafeParents(file.getParent());requireNode(file,false);
    }

    /** CREATE_NEW和initial属性在同一次open中生效，不先写正文再收紧权限。 */
    public static FileChannel createFile(Path requested) throws IOException {
        Path file=normalized(requested);requireDirectory(file.getParent());
        FileChannel channel=FileChannel.open(file,Set.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),ownerAttribute(file.getParent(),false));
        try { requireRegularFile(file);return channel; }
        catch(IOException|RuntimeException rejected) {
            channel.close();
            // 不删除无法再证明身份的节点，保留失败现场供人工核对。
            throw rejected;
        }
    }

    public static Path createTempFile(Path directory,String prefix,String suffix) throws IOException {
        Path root=normalized(directory);requireDirectory(root);
        if(prefix==null||suffix==null||prefix.length()+suffix.length()>160
                ||prefix.contains("/")||prefix.contains("\\")||suffix.contains("/")||suffix.contains("\\")
                ||(prefix+suffix).chars().anyMatch(c->c<32))throw new IOException("Runner临时文件名称无效");
        for(int attempt=0;attempt<10;attempt++) {
            Path temporary=root.resolve(prefix+UUID.randomUUID()+suffix);
            try(FileChannel ignored=createFile(temporary)){return temporary;}
            catch(FileAlreadyExistsException collision){/* UUID冲突不覆盖已有文件。 */}
        }
        throw new IOException("Runner私有临时文件创建冲突");
    }

    /** 仅同一私有目录内写新文件；失败时不发布部分正文，已有目标一律拒绝。 */
    public static Path writeAtomic(Path directory,String filename,byte[] bytes) throws IOException {
        Path root=normalized(directory);requireDirectory(root);
        if(filename==null||filename.isBlank()||filename.length()>255||filename.equals(".")||filename.equals("..")
                ||filename.contains("/")||filename.contains("\\")||filename.chars().anyMatch(c->c<32)
                ||bytes==null)throw new IOException("Runner私有文件名称或正文无效");
        Path destination=root.resolve(filename);
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(destination.toString());
        Path temporary=root.resolve(".pending-"+UUID.randomUUID()+".tmp");
        boolean moved=false;
        try {
            try(FileChannel channel=createFile(temporary)) {
                ByteBuffer content=ByteBuffer.wrap(bytes);while(content.hasRemaining())channel.write(content);channel.force(true);
            }
            requireDirectory(root);requireRegularFile(temporary);
            if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(destination.toString());
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);moved=true;
            requireRegularFile(destination);return destination;
        } finally {
            if(!moved&&Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)) {
                requireRegularFile(temporary);Files.delete(temporary);
            }
        }
    }

    private static Path normalized(Path path) throws IOException {
        if(path==null)throw new IOException("Runner私有路径缺失");return path.toAbsolutePath().normalize();
    }

    private static void requireCanonical(Path node) throws IOException {
        if(Files.isSymbolicLink(node)||!node.equals(node.toRealPath()))throw new IOException("Runner私有路径包含链接或目录跳转");
    }

    /** 父目录即使可读，也不能由普通其他账号删除、替换或修改其ACL。 */
    private static void requireSafeParents(Path requested) throws IOException {
        if(requested==null)throw new IOException("Runner私有节点没有安全父目录");
        for(Path parent=normalized(requested);parent!=null;parent=parent.getParent()) {
            requireCanonical(parent);
            if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("Runner私有父节点不是目录");
            UserPrincipal current=currentOwner(parent),owner=Files.getOwner(parent,LinkOption.NOFOLLOW_LINKS);
            if(!owner.equals(current)&&!systemAuthority(parent,owner))throw new IOException("Runner父目录所有者不在可信本机边界内");
            PosixFileAttributeView posix=Files.getFileAttributeView(parent,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(posix!=null) {
                Set<PosixFilePermission> permissions=posix.readAttributes().permissions();
                if(permissions.contains(PosixFilePermission.GROUP_WRITE)||permissions.contains(PosixFilePermission.OTHERS_WRITE))
                    throw new IOException("Runner父目录允许其他账号替换节点");
                continue;
            }
            AclFileAttributeView view=Files.getFileAttributeView(parent,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(view==null)throw new IOException("Runner文件系统无法证明父目录私有权限");
            for(AclEntry entry:view.getAcl()) {
                if(entry.type()!=AclEntryType.ALLOW||entry.flags().contains(AclEntryFlag.INHERIT_ONLY)
                        ||entry.principal().equals(current)||systemAuthority(parent,entry.principal()))continue;
                for(AclEntryPermission permission:entry.permissions()) {
                    // Windows卷根AddSubdirectory仅能建立旁支，不能替换已有私有节点。
                    if(parent.getParent()==null&&permission==AclEntryPermission.APPEND_DATA)continue;
                    if(MUTATIONS.contains(permission))throw new IOException("Runner父目录授予其他账号删除、替换或修改权限");
                }
            }
        }
    }

    private static void requireNode(Path path,boolean directory) throws IOException {
        requireCanonical(path);
        if(directory?!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Runner私有节点类型改变");
        UserPrincipal owner=currentOwner(path);
        if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw new IOException("Runner私有节点属于其他账号");
        PosixFileAttributeView posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) {
            if(!posix.readAttributes().permissions().equals(privateMode(directory)))throw new IOException("Runner既有节点不是私有0700/0600权限");
            return;
        }
        AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(view==null)throw new IOException("Runner文件系统无法证明节点私有ACL");
        EnumSet<AclEntryPermission> actual=EnumSet.noneOf(AclEntryPermission.class);
        for(AclEntry entry:view.getAcl()) {
            if(entry.type()==AclEntryType.ALLOW&&!entry.principal().equals(owner)&&!entry.permissions().isEmpty())
                throw new IOException("Runner既有节点授予其他账号访问权限");
            if(entry.type()==AclEntryType.DENY&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY))
                throw new IOException("Runner既有节点不能证明所有者访问权限");
            if(entry.type()==AclEntryType.ALLOW&&entry.principal().equals(owner)&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY))actual.addAll(entry.permissions());
        }
        if(!actual.containsAll(Set.of(AclEntryPermission.READ_DATA,AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA))
                ||actual.contains(AclEntryPermission.EXECUTE)!=directory)
            throw new IOException("Runner所有者数据权限不符合私有节点用途");
    }

    private static UserPrincipal currentOwner(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(CURRENT_USER);
    }
    private static boolean systemAuthority(Path path,UserPrincipal principal) {
        return AUTHORITIES.computeIfAbsent(path.getFileSystem(),filesystem->{
            List<UserPrincipal> trusted=new ArrayList<>();
            boolean posix=filesystem.supportedFileAttributeViews().contains("posix");
            for(String name:posix?List.of("root"):List.of("NT AUTHORITY\\SYSTEM","BUILTIN\\Administrators","NT SERVICE\\TrustedInstaller","S-1-5-18","S-1-5-32-544"))
                try { trusted.add(filesystem.getUserPrincipalLookupService().lookupPrincipalByName(name)); }
                catch(IOException unavailable){/* 未解析的主体不会视为特权系统账号。 */}
            return List.copyOf(trusted);
        }).contains(principal);
    }
    private static Set<PosixFilePermission> privateMode(boolean directory) {
        return PosixFilePermissions.fromString(directory?"rwx------":"rw-------");
    }
    private static FileAttribute<?> ownerAttribute(Path parent,boolean directory) throws IOException {
        if(Files.getFileAttributeView(parent,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)
            return PosixFilePermissions.asFileAttribute(privateMode(directory));
        if(Files.getFileAttributeView(parent,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)==null)
            throw new IOException("Runner文件系统不支持原子私有权限创建");
        List<AclEntry> entries=ownerAcl(currentOwner(parent),directory);
        return new FileAttribute<List<AclEntry>>() {
            public String name(){return "acl:acl";}public List<AclEntry> value(){return entries;}
        };
    }
    private static List<AclEntry> ownerAcl(UserPrincipal owner,boolean directory) {
        EnumSet<AclEntryPermission> data=EnumSet.of(AclEntryPermission.READ_DATA,AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,AclEntryPermission.READ_NAMED_ATTRS,AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.READ_ATTRIBUTES,AclEntryPermission.WRITE_ATTRIBUTES,AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER,AclEntryPermission.DELETE,AclEntryPermission.SYNCHRONIZE);
        AclEntry file=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(data).build();
        if(!directory)return List.of(file);
        EnumSet<AclEntryPermission> directories=EnumSet.copyOf(data);directories.add(AclEntryPermission.EXECUTE);directories.add(AclEntryPermission.DELETE_CHILD);
        return List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(directories).setFlags(AclEntryFlag.DIRECTORY_INHERIT).build(),
                AclEntry.newBuilder(file).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.INHERIT_ONLY).build());
    }
}
