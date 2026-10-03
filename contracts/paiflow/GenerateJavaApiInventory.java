import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** 只解析源码语法树；不编译、启动控制器或解析真实配置。 */
@SuppressWarnings("unchecked")
class GenerateJavaApiInventory {
    static final List<Object> classes = new ArrayList<>(), endpoints = new ArrayList<>(), errors = new ArrayList<>();
    static final Set<String> mappingNames = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "OptionsMapping", "HeadMapping");
    static String rootLabel;
    static Path workspace;
    static Map<String,Object> obj(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m; }
    static String simple(String s){int p=s.lastIndexOf('.');return p<0?s:s.substring(p+1);}
    static List<String> annotations(ModifiersTree m){return m.getAnnotations().stream().map(Object::toString).toList();}
    static Map<String,ExpressionTree> args(AnnotationTree a){Map<String,ExpressionTree> r=new LinkedHashMap<>();for(ExpressionTree e:a.getArguments()){if(e instanceof AssignmentTree x)r.put(x.getVariable().toString(),x.getExpression());else r.put("value",e);}return r;}
    static List<String> literals(ExpressionTree e){if(e==null)return List.of("");if(e instanceof LiteralTree l&&l.getValue() instanceof String s)return List.of(s);if(e instanceof NewArrayTree a){List<String> r=new ArrayList<>();if(a.getInitializers()!=null)for(ExpressionTree x:a.getInitializers())r.addAll(literals(x));return r;}return List.of("<UNRESOLVED:"+e+">");}
    static List<Map<String,Object>> mappings(ModifiersTree m){List<Map<String,Object>> r=new ArrayList<>();for(AnnotationTree a:m.getAnnotations()){String n=simple(a.getAnnotationType().toString());if(!mappingNames.contains(n))continue;Map<String,ExpressionTree> v=args(a);List<String> methods=new ArrayList<>();if(n.equals("RequestMapping")){ExpressionTree x=v.get("method");if(x==null)methods.add("ANY");else if(x instanceof NewArrayTree ar){for(ExpressionTree z:ar.getInitializers())methods.add(simple(z.toString()));}else methods.add(simple(x.toString()));}else methods.add(n.substring(0,n.length()-7).toUpperCase());r.add(obj("annotation",a.toString(),"paths",literals(v.containsKey("path")?v.get("path"):v.get("value")),"methods",methods));}return r;}
    static String join(String a,String b){if(a.contains("<UNRESOLVED")||b.contains("<UNRESOLVED"))return a+b;String p=(a+"/"+b).replaceAll("/+","/");return p.length()>1&&p.endsWith("/")?p.substring(0,p.length()-1):p;}
    static Object jsonSafe(Object v){return v;}
    static String json(Object v){if(v==null)return "null";if(v instanceof String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}if(v instanceof Number||v instanceof Boolean)return v.toString();if(v instanceof Map<?,?> m){List<String>x=new ArrayList<>();for(var e:m.entrySet())x.add(json(e.getKey().toString())+":"+json(e.getValue()));return "{"+String.join(",",x)+"}";}if(v instanceof Iterable<?> i){List<String>x=new ArrayList<>();for(Object e:i)x.add(json(e));return "["+String.join(",",x)+"]";}return json(v.toString());}
    public static void main(String[] input)throws Exception{
        workspace=Path.of(input[0]).toAbsolutePath();rootLabel=input[1];Path source=workspace.resolve(rootLabel);Path output=Path.of(input[2]);
        List<Path> files;try(var stream=Files.walk(source)){files=stream.filter(p->p.toString().endsWith(".java")&&p.toString().replace('\\','/').contains("/src/main/java/")).sorted().toList();}
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)){
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-encoding","UTF-8"),null,manager.getJavaFileObjectsFromPaths(files));
            Trees trees=Trees.instance(task);SourcePositions pos=trees.getSourcePositions();
            for(CompilationUnitTree unit:task.parse()){
                Path fp=Path.of(unit.getSourceFile().toUri());String file=workspace.relativize(fp).toString().replace('\\','/');String text=Files.readString(fp,StandardCharsets.UTF_8);String pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();List<String> imports=unit.getImports().stream().map(x->x.getQualifiedIdentifier().toString()).toList();
                new TreeScanner<Void,String>(){
                    int line(Tree t){return (int)unit.getLineMap().getLineNumber(pos.getStartPosition(unit,t));}
                    public Void visitClass(ClassTree c,String parent){String name=(parent==null?pkg:parent)+"."+c.getSimpleName();List<Object> fields=new ArrayList<>();
                        for(Tree member:c.getMembers())if(member instanceof VariableTree f){boolean isStatic=f.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC);if(!isStatic)fields.add(obj("name",f.getName().toString(),"type",f.getType()==null?"":f.getType().toString(),"annotations",annotations(f.getModifiers()),"line",line(f)));}
                        classes.add(obj("name",name,"simpleName",c.getSimpleName().toString(),"kind",c.getKind().toString(),"file",file,"line",line(c),"imports",imports,"extends",c.getExtendsClause()==null?null:c.getExtendsClause().toString(),"fields",fields));
                        // 原项目还通过 WebSocketHandlerRegistry 注册端点，不能只计 Controller。
                        for(Tree member:c.getMembers())if(member instanceof MethodTree wsMethod&&wsMethod.getBody()!=null)new TreeScanner<Void,Void>(){
                            public Void visitMethodInvocation(MethodInvocationTree call,Void v){
                                if(call.getMethodSelect().toString().endsWith(".addHandler")&&wsMethod.getName().toString().equals("registerWebSocketHandlers")&&call.getArguments().size()>1){
                                    for(int i=1;i<call.getArguments().size();i++)for(String path:literals(call.getArguments().get(i)))endpoints.add(obj("id",rootLabel+":"+file+":"+line(call)+":WEBSOCKET:"+path,"method","WEBSOCKET","path",path,"controller",name,"handler",call.getArguments().get(0).toString(),"file",file,"line",line(call),"classAnnotations",annotations(c.getModifiers()),"methodAnnotations",annotations(wsMethod.getModifiers()),"parameters",List.of(),"returnType","WebSocketHandler","imports",imports,"bodyCalls",List.of(call.getMethodSelect().toString()),"sourceSignals",List.of(),"registrationKind","WebSocketHandlerRegistry.addHandler"));
                                }return super.visitMethodInvocation(call,v);
                            }
                        }.scan(wsMethod.getBody(),null);
                        boolean controller=c.getModifiers().getAnnotations().stream().anyMatch(a->Set.of("RestController","Controller").contains(simple(a.getAnnotationType().toString())));
                        if(controller){List<Map<String,Object>> bases=mappings(c.getModifiers());if(bases.isEmpty())bases=List.of(obj("paths",List.of(""),"methods",List.of("ANY")));List<String> classAnnotations=annotations(c.getModifiers());
                            for(Tree member:c.getMembers())if(member instanceof MethodTree method){List<Map<String,Object>> maps=mappings(method.getModifiers());if(maps.isEmpty())continue;List<Object> params=new ArrayList<>();for(VariableTree p:method.getParameters())params.add(obj("name",p.getName().toString(),"type",p.getType().toString(),"annotations",annotations(p.getModifiers()),"line",line(p)));
                                List<String> calls=new ArrayList<>();List<String> signals=new ArrayList<>();if(method.getBody()!=null)new TreeScanner<Void,Void>(){
                                    public Void visitMethodInvocation(MethodInvocationTree t,Void v){String call=t.getMethodSelect().toString();if(!calls.contains(call))calls.add(call);if(t.getArguments().size()==1&&t.getArguments().get(0) instanceof LiteralTree l&&l.getValue()==null&&call.endsWith("success"))signals.add("success_null_return_candidate");return super.visitMethodInvocation(t,v);}
                                    public Void visitReturn(ReturnTree t,Void v){if(t.getExpression() instanceof LiteralTree l&&l.getValue()==null)signals.add("literal_null_return_branch");return super.visitReturn(t,v);}
                                    public Void visitNewClass(NewClassTree t,Void v){if(t.getIdentifier().toString().contains("UnsupportedOperationException"))signals.add("throws_unsupported_operation_candidate");return super.visitNewClass(t,v);}
                                }.scan(method.getBody(),null);
                                long start=pos.getStartPosition(unit,method),end=pos.getEndPosition(unit,method);String body=(start>=0&&end>=start)?text.substring((int)start,(int)end):"";if(body.contains("TODO"))signals.add("todo_marker_in_method");
                                for(Map<String,Object>b:bases)for(Map<String,Object>m:maps)for(String base:(List<String>)b.get("paths"))for(String path:(List<String>)m.get("paths"))for(String verb:(List<String>)m.get("methods"))endpoints.add(obj("id",rootLabel+":"+file+":"+line(method)+":"+verb+":"+join(base,path),"method",verb,"path",join(base,path),"controller",name,"handler",method.getName().toString(),"file",file,"line",line(method),"classAnnotations",classAnnotations,"methodAnnotations",annotations(method.getModifiers()),"parameters",params,"returnType",method.getReturnType()==null?null:method.getReturnType().toString(),"imports",imports,"bodyCalls",calls,"sourceSignals",new LinkedHashSet<>(signals)));
                            }
                        }
                        return super.visitClass(c,name);
                    }
                }.scan(unit,null);
            }
        }
        for(Diagnostic<? extends JavaFileObject>d:diagnostics.getDiagnostics())errors.add(obj("kind",d.getKind().toString(),"file",d.getSource()==null?null:workspace.relativize(Path.of(d.getSource().toUri())).toString().replace('\\','/'),"line",d.getLineNumber(),"code",d.getCode(),"message",d.getMessage(Locale.ROOT)));
        Files.writeString(output,json(obj("javaFiles",files.size(),"endpoints",endpoints,"types",classes,"parseDiagnostics",errors)),StandardCharsets.UTF_8);
        System.out.println("Java AST files="+files.size()+", endpoint variants="+endpoints.size()+", parse diagnostics="+errors.size());
    }
}
