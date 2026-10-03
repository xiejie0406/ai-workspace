"""实际私有RequestAttributes摘录＋原ss/PermissionContextHolder的隔离检查；不加载新桥。"""
from pathlib import Path
import hashlib
import json
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[6]
CHECKS = Path(__file__).resolve().parent
STREAM = ROOT / 'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyDebugStreamService.java'
SS = ROOT / 'ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/PermissionService.java'
CONTEXT = ROOT / 'ruoyi-backend/ruoyi-framework/src/main/java/com/ruoyi/framework/security/context/PermissionContextHolder.java'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
before = {str(p): sha(p) for p in [STREAM, SS, CONTEXT]}
source = STREAM.read_text(encoding='utf-8-sig')
start = source.index('private static final class PermissionRequestScope')
opening = source.index('{', start)
depth = 0
for pos in range(opening, len(source)):
    if source[pos] == '{': depth += 1
    elif source[pos] == '}': depth -= 1
    if depth == 0:
        scope_source = source[start:pos+1]
        break
else: raise AssertionError('私有scope摘录不完整')
xml = ROOT / 'ruoyi-backend/ruoyi-paiflow/target/surefire-reports/TEST-com.ruoyi.paiflow.adapter.legacy.LegacyAgentContractTest.xml'
properties = {p.get('name'): p.get('value') for p in ET.parse(xml).getroot().findall('./properties/property')}
classpath = properties['java.class.path']
temp = Path(tempfile.mkdtemp(prefix='paiflow-debug-permission-'))
java = r'''
import java.util.*;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.security.context.PermissionContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
public class DebugPermissionScopeProbe {
SCOPE_PLACEHOLDER
public static void main(String[] args){
  var result=new JSONObject(); var originalSecurity=SecurityContextHolder.getContext(); var originalRequest=RequestContextHolder.getRequestAttributes();
  try {
    var previous=SecurityContextHolder.createEmptyContext();var prior=new LoginUser();prior.setUserId(7L);prior.setPermissions(Set.of("prior:only"));previous.setAuthentication(new UsernamePasswordAuthenticationToken(prior,null,prior.getAuthorities()));
    var outer=new PermissionRequestScope();outer.setAttribute("outer", "preserved",RequestAttributes.SCOPE_REQUEST); SecurityContextHolder.setContext(previous);RequestContextHolder.setRequestAttributes(outer);
    for(long actor:new long[]{42,43}){
      var login=new LoginUser();login.setUserId(actor);login.setPermissions(Set.of("paiflow:run:query"));var current=SecurityContextHolder.createEmptyContext();current.setAuthentication(new UsernamePasswordAuthenticationToken(login,null,login.getAuthorities()));
      SecurityContextHolder.setContext(current);RequestContextHolder.resetRequestAttributes(); var ss=new PermissionService();boolean missingScopeRejected=false;
      try{ss.hasPermi("paiflow:run:query");}catch(IllegalStateException e){missingScopeRejected=true;}
      var scope=new PermissionRequestScope();RequestContextHolder.setRequestAttributes(scope);
      boolean granted=ss.hasPermi("paiflow:run:query");String grantedLabel=PermissionContextHolder.getContext();boolean denied=!ss.hasPermi("paiflow:run:create");String deniedLabel=PermissionContextHolder.getContext();
      login.setPermissions(Set.of());scope.setAttribute("PERMISSION_CONTEXT","*:*:*",RequestAttributes.SCOPE_REQUEST);boolean labelCannotGrant=!ss.hasPermi("paiflow:run:create");
      boolean noHttpSession=false;try{scope.getSessionId();}catch(IllegalStateException e){noHttpSession=true;}
      SecurityContextHolder.setContext(previous);RequestContextHolder.setRequestAttributes(outer);scope.close();
      boolean restored=SecurityUtils.getUserId()==7L && RequestContextHolder.getRequestAttributes()==outer && "preserved".equals(outer.getAttribute("outer",RequestAttributes.SCOPE_REQUEST));
      var row=JSONObject.of("missingScopeRejected",missingScopeRejected,"granted",granted,"grantedLabel",grantedLabel,"denied",denied,"deniedLabel",deniedLabel,"labelCannotGrant",labelCannotGrant,"noHttpSession",noHttpSession,"closedScopeEmpty",scope.getAttributeNames(RequestAttributes.SCOPE_REQUEST).length==0,"previousContextsRestored",restored);
      result.put(Long.toString(actor),row);
      if(!missingScopeRejected || !granted || !denied || !labelCannotGrant || !noHttpSession || !restored || !"paiflow:run:query".equals(grantedLabel) || !"paiflow:run:create".equals(deniedLabel))throw new AssertionError(row.toJSONString());
    }
    System.out.println(result.toJSONString());
  } finally {SecurityContextHolder.setContext(originalSecurity);if(originalRequest==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(originalRequest);}
}
}
'''.replace('SCOPE_PLACEHOLDER', scope_source)
source_file = temp / 'DebugPermissionScopeProbe.java'
source_file.write_text(java, encoding='utf-8')
jdk = Path('C:/Users/admin/AppData/Local/Programs/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin')
compile_result = subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-proc:none','-classpath',classpath,'-d',str(temp),str(source_file),str(SS),str(CONTEXT)],capture_output=True,text=True,encoding='utf-8')
run_result = subprocess.run([str(jdk/'java.exe'),'-classpath',str(temp)+';'+classpath,'DebugPermissionScopeProbe'],capture_output=True,text=True,encoding='utf-8') if compile_result.returncode==0 else None
after = {str(p): sha(p) for p in [STREAM, SS, CONTEXT]}
result = {'scope':'实际候选私有RequestAttributes摘录＋原PermissionService/PermissionContextHolder，在Temp编译；LoginUser/Spring依既有真实Mavenclasspath。不是withSession/TokenService/Bridge整体执行，HTTP/DB/Redis/session调用均0。',
          'compiledSource':str(source_file),'privateScopeSha256':hashlib.sha256(scope_source.encode()).hexdigest(),
          'classpathOrigin':str(xml),'classpathEntries':len(classpath.split(';')),'compileExit':compile_result.returncode,'compileStderr':compile_result.stderr,
          'runExit':None if run_result is None else run_result.returncode,'stdout':None if run_result is None else run_result.stdout,'stderr':None if run_result is None else run_result.stderr,
          'sourceBefore':before,'sourceAfter':after,'changed':[p for p in before if before[p]!=after[p]]}
output = CHECKS/'原调试桥私有权限作用域独立核对.json'
output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
assert compile_result.returncode==0 and run_result is not None and run_result.returncode==0 and not result['changed']
