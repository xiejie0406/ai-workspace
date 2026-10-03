
import com.paicli.runner.RunnerPrivateDirectory;
import com.paicli.runner.ResourceFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
public class LegacyImportPrivateFiles {
 public static void main(String[] a) throws Exception {
  Path p=Path.of(a[1]).toAbsolutePath().normalize();ObjectMapper j=new ObjectMapper();
  if(a[0].equals("mkdir")){System.out.println(RunnerPrivateDirectory.createDirectories(p).toRealPath());return;}
  if(a[0].equals("put")){RunnerPrivateDirectory.writeAtomic(p.getParent(),p.getFileName().toString(),System.in.readAllBytes());return;}
  if(a[0].equals("require-file")){RunnerPrivateDirectory.requireRegularFile(p);return;}
  if(a[0].equals("fingerprint")){RunnerPrivateDirectory.requireDirectory(p);var r=new ResourceFingerprint(p);System.out.println(j.writeValueAsString(Map.of("root",r.root().toString(),"rootFingerprint",r.rootFingerprint(),"source","actual frozen native ResourceFingerprint constructor")));return;}
  RunnerPrivateDirectory.requireDirectory(p);List<Object> nodes=new ArrayList<>();
  for(Path n=p;n!=null;n=n.getParent())nodes.add(view(n));
  if(a[0].equals("check-tree"))try(var s=Files.walk(p)){
   for(Path n:s.sorted().toList()){
    if(Files.isSymbolicLink(n)||!n.equals(n.toRealPath()))throw new Exception("样本含链接或目录跳转");
    if(Files.isDirectory(n,LinkOption.NOFOLLOW_LINKS))RunnerPrivateDirectory.requireDirectory(n);
    else if(Files.isRegularFile(n,LinkOption.NOFOLLOW_LINKS)){
     var owner=Files.getOwner(n);var acl=Files.getFileAttributeView(n,AclFileAttributeView.class);
     if(!owner.equals(Files.getOwner(p))||acl==null)throw new Exception("样本所有者不一致或无ACL");
     for(var e:acl.getAcl())if(e.type()==AclEntryType.ALLOW&&!e.principal().equals(owner)&&!e.permissions().isEmpty())throw new Exception("样本文件非私有");
    }else throw new Exception("样本非普通文件");nodes.add(view(n));
   }
  }
  System.out.println(j.writeValueAsString(Map.of("canonical",p.toRealPath().toString(),"nodes",nodes,"checked",true)));
 }
 static Object view(Path p)throws Exception{
  List<Object> entries=new ArrayList<>();var v=Files.getFileAttributeView(p,AclFileAttributeView.class);
  if(v!=null)for(var e:v.getAcl())entries.add(Map.of("principal",e.principal().getName(),"type",e.type().name(),"flags",e.flags().stream().map(Enum::name).sorted().toList(),"permissions",e.permissions().stream().map(Enum::name).sorted().toList()));
  return Map.of("path",p.toString(),"owner",Files.getOwner(p).getName(),"acl",entries);
 }
}
