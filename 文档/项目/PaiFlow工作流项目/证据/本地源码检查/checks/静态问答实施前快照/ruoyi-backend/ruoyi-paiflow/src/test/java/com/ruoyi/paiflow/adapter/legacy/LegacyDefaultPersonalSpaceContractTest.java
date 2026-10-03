package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.paiflow.service.SpaceService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 原GET visit是明确的访问记录写动作；GET list/last只读，默认回执不能携带隐藏具名ID。 */
class LegacyDefaultPersonalSpaceContractTest {
    @Test void emptyAndMissingVisitParameterBothSelectOriginalDefault()throws Exception{
        var service=mock(SpaceService.class);var mvc=MockMvcBuilders.standaloneSetup(new LegacySpaceController(service)).build();
        mvc.perform(get("/paiflow/legacy/space/visit-space").param("spaceId","")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/paiflow/legacy/space/visit-space")).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());verify(service,times(2)).visit(null);
    }
    @Test void defaultLastUsesDedicatedComparisonAndDoesNotSelectFirstNamedList()throws Exception{
        var service=mock(SpaceService.class);var mvc=MockMvcBuilders.standaloneSetup(new LegacySpaceController(service)).build();
        mvc.perform(get("/paiflow/legacy/space/get-last-visit-space")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").doesNotExist());verify(service).last();verify(service,never()).recent();verify(service,never()).visit(any());
    }
    @Test void namedListIsKeptAndReadNeverInitializesDefault()throws Exception{
        var service=mock(SpaceService.class);when(service.listPersonal(null,false)).thenReturn(List.of());var mvc=MockMvcBuilders.standaloneSetup(new LegacySpaceController(service)).build();
        mvc.perform(get("/paiflow/legacy/space/personal-list")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());verify(service).listPersonal(null,false);verify(service,never()).visit(any());
    }
}
