import java.lang.reflect.*;
import java.util.*;
/** 仅checks独立运行已编译的真实H2 test方法，Maven/JUnit正式结果另存；不写共享target。 */
public class CurrentAuthorityProbe {
    public static void main(String[] args)throws Exception {
        Class<?> type=Class.forName("com.ruoyi.paiflow.service.CurrentAuthorityPersistenceTest");
        var setup=type.getDeclaredMethod("setup");var close=type.getDeclaredMethod("close");setup.setAccessible(true);close.setAccessible(true);
        var cases=Arrays.stream(type.getDeclaredMethods()).filter(m->m.isAnnotationPresent(org.junit.jupiter.api.Test.class)).sorted(Comparator.comparing(Method::getName)).toList();
        int passed=0;
        for(Method test:cases){var fixture=type.getDeclaredConstructor().newInstance();test.setAccessible(true);try{setup.invoke(fixture);test.invoke(fixture);passed++;System.out.println("PASS "+test.getName());}catch(InvocationTargetException ex){System.out.println("FAIL "+test.getName());ex.getCause().printStackTrace();throw ex;}finally{close.invoke(fixture);}}
        System.out.println("ISOLATED_METHOD_PROBE: "+passed+"/"+cases.size()+"; actual H2/Mapper platform chain; no Maven/shared target or network.");
    }
}
