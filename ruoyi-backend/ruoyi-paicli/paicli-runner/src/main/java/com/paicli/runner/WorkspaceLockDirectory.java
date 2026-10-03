package com.paicli.runner;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 工作根互斥锁的目录边界：不修改既有用户 home 或共享 .cache 的权限。 */
final class WorkspaceLockDirectory {
    record Held(java.nio.channels.FileChannel channel, java.nio.channels.FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { try { if(lock.isValid()) lock.release(); } finally { channel.close(); } }
    }
    private static final Set<AclEntryPermission> MUTATIONS = EnumSet.of(AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
    private WorkspaceLockDirectory() { }
    static Path prepare(Path home) throws IOException {
        Path canonicalHome = home.toAbsolutePath().normalize();
        requireDirectory(canonicalHome, false);
        Path cache = directory(canonicalHome.resolve(".cache"), false);
        Path managed = directory(cache.resolve("ruoyi-paicli"), true);
        Path locks = directory(managed.resolve("workspace-locks"), true);
        requireParents(locks);
        return locks;
    }
    private static Path directory(Path path, boolean privateOnly) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            FileAttribute<?> initial = initialPermissions(path.getParent(), true);
            try { Files.createDirectory(path, initial); }
            catch (FileAlreadyExistsException race) { /* 重新验证已存在节点。 */ }
        }
        requireDirectory(path, privateOnly);
        return path;
    }
    static void requireParents(Path locks) throws IOException {
        requireDirectory(locks, true);
        requireDirectory(locks.getParent(), true);
        requireDirectory(locks.getParent().getParent(), false);
        requireDirectory(locks.getParent().getParent().getParent(), false);
    }
    static void prepareFile(Path file) throws IOException {
        requireParents(file.getParent());
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createFile(file, initialPermissions(file.getParent(), false)); }
            catch (FileAlreadyExistsException race) { /* 重新验证竞争创建的实际节点。 */ }
        }
        requireFile(file);
    }
    static Held acquire(Path directory, String name) throws IOException {
        Path file=directory.resolve(name); prepareFile(file);
        var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        java.nio.channels.FileLock lock=null;
        try {
            requireFile(file); lock=channel.tryLock(); if(lock==null) throw new IOException("Runner工作根或派生状态已有执行进程");
            requireFile(file); return new Held(channel,lock);
        } catch(IOException|RuntimeException failure) {
            if(lock!=null&&lock.isValid()) lock.release(); channel.close();
            throw new IOException("Runner工作根或派生状态互斥锁不可用",failure);
        }
    }
    static void requireFile(Path file) throws IOException {
        requireParents(file.getParent());
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("工作根锁文件必须是普通文件");
        requirePermissions(file, true);
    }
    private static void requireDirectory(Path path, boolean privateOnly) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink()) throw new IOException("工作根锁父节点必须是实际目录");
        requirePermissions(path, privateOnly);
    }
    private static UserPrincipal current(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }
    private static void requirePermissions(Path path, boolean privateOnly) throws IOException {
        if (!path.toAbsolutePath().normalize().equals(path.toRealPath())) throw new IOException("工作根锁路径包含重解析跳转");
        UserPrincipal user = current(path);
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(user)) throw new IOException("工作根锁节点不属于当前本机用户");
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || privateOnly && permissions.stream().anyMatch(p -> !p.name().startsWith("OWNER_")))
                throw new IOException("工作根锁父目录或文件授予其他用户权限");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("文件系统不能证明工作根锁权限");
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW || entry.flags().contains(AclEntryFlag.INHERIT_ONLY)
                    || entry.principal().equals(user) || operatingSystemAuthority(path, entry.principal())) continue;
            if (privateOnly && !entry.permissions().isEmpty() || entry.permissions().stream().anyMatch(MUTATIONS::contains))
                throw new IOException("工作根锁父目录或文件包含非所有者可变授权");
        }
    }
    private static boolean operatingSystemAuthority(Path path, UserPrincipal principal) {
        // Windows SYSTEM/内置管理员是已拥有本机特权的系统边界，不包括普通组或任意同名账号。
        var lookup = path.getFileSystem().getUserPrincipalLookupService();
        for (String trusted : List.of("NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators", "S-1-5-18", "S-1-5-32-544")) {
            try { if (principal.equals(lookup.lookupPrincipalByName(trusted))) return true; }
            catch (IOException unavailable) { /* 解析失败不会放行不认识的主体。 */ }
        }
        return false;
    }
    private static FileAttribute<?> initialPermissions(Path parent, boolean directory) throws IOException {
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null)
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        if (Files.getFileAttributeView(parent, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null)
            throw new IOException("文件系统不能原子创建私有工作根锁");
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current(parent))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        List<AclEntry> entries = List.of(entry.build());
        return new FileAttribute<List<AclEntry>>() { public String name() { return "acl:acl"; } public List<AclEntry> value() { return entries; } };
    }
}
