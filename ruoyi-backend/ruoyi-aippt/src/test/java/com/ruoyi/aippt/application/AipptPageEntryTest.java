package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.domain.AipptException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AipptPageEntryTest {
    @Test void allPageCommandsWithoutOutlineAreRejectedBeforeWritesOrDeckVersionChange() throws Exception {
        AipptPersistenceFixture f=new AipptPersistenceFixture();
        for(String action:java.util.List.of("add","duplicate","remove","order")) {
            assertThrows(AipptException.class,()->f.service.pages(f.project,"missing",action,f.owner,action,JSONObject.of("deck_revision",1,"slide_ids",new JSONArray())));
            assertTrue(f.db.children("slide",f.project).isEmpty());
            assertEquals(1,f.project().getLongValue("deck_revision"));
        }
    }
    @Test void blankPageWithExistingOutlineSynchronizesOriginalDefaultOutlineAndContent() throws Exception {
        AipptPersistenceFixture f=new AipptPersistenceFixture();
        JSONObject outline=f.outline("draft");
        JSONObject deck=f.service.pages(f.project,null,"add",f.owner,"add",JSONObject.of("deck_revision",1));
        JSONObject slide=deck.getJSONArray("slides").getJSONObject(0);
        JSONObject page=f.db.get("outline",outline.getString("id")).getJSONArray("pages").getJSONObject(0);
        assertEquals(slide.get("outline_page_id"),page.get("id"));
        assertEquals("补充这一页想讲清楚的事",page.getString("objective"));
        assertEquals(JSONArray.of("待补充要点","待补充要点"),page.getJSONArray("key_points"));
        assertEquals(2,slide.getJSONArray("blocks").size());
        assertEquals("title",slide.getJSONObject("layout_tree").getJSONArray("children").getJSONObject(0).getString("text_style"));
        assertEquals(1,f.project().getIntValue("page_count"));
    }
}
