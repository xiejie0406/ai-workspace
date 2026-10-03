package com.paicli.tui.pane;

import com.googlecode.lanterna.gui2.Label;
import com.paicli.config.PaiCliConfig;
import com.paicli.render.StatusInfo;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CentralTuiPresentationTest {
    @Test void centralFileTreeStartsUnloadedAndDisplaysOnlyReceivedActualEntries() {
        var tree=new FileTreePane(new PaiCliConfig(),true);
        var list=(com.googlecode.lanterna.gui2.CheckBoxList<?>)tree.getChildrenList().get(0);
        assertEquals(1,list.getItemCount());assertEquals("等待中心工作区目录列表",list.getItemAt(0));
        tree.showCenterEntries(List.of(new ToolRegistry.DirectoryEntry("actual.java","FILE"),new ToolRegistry.DirectoryEntry("src","DIRECTORY")));
        assertEquals(2,list.getItemCount());assertEquals("[F] actual.java",list.getItemAt(0));assertEquals("[D] src",list.getItemAt(1));
        tree.refresh(); assertEquals(2,list.getItemCount());
    }
    @Test void statusDisplaysActualModelWindowModeAndUnknownBudgetHonestly() {
        var pane=new StatusPane(new PaiCliConfig(),null);
        pane.updateStatus(StatusInfo.tokens("provider/full-model-id",128000,32000,50000,10000,1200,null,true,1200,"running").withSessionMode("PLAN"));
        assertEquals("🤖 provider/full-model-id",((Label)pane.getChildrenList().get(0)).getText());
        assertTrue(((Label)pane.getChildrenList().get(1)).getText().contains("32000/128000"));
        assertEquals("🔄 PLAN",((Label)pane.getChildrenList().get(2)).getText());
        pane.updateStatus(StatusInfo.idle("actual",0,true));assertTrue(((Label)pane.getChildrenList().get(1)).getText().contains("未上报"));
    }
}
