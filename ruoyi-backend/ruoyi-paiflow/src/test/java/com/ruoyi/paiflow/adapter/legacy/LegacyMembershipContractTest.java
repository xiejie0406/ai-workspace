package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.service.MembershipService;
import com.ruoyi.paiflow.service.SpaceService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原legacy传输契约的MVC隔离检查；不启动HTTP服务，不替代真实套餐/通知/页面验收。 */
class LegacyMembershipContractTest {
    @Test void teamCreationKeepsLongDataAndSpaceMemberPageKeepsMybatisPageShape() throws Exception {
        var spaces=mock(SpaceService.class);var members=mock(MembershipService.class);
        when(spaces.createTeam("原团队","avatar")).thenReturn(71L);
        var row=new JSONObject();row.put("id",22);row.put("uid","ruoyi:43");row.put("role",2);
        when(members.memberPage(eq(9L),any())).thenReturn(new MembershipService.Page<>(List.of(row),1,1,10,1));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new LegacyEnterpriseController(spaces),new LegacySpaceMemberController(members)).build();
        mvc.perform(post("/paiflow/legacy/enterprise/create").contentType("application/json").content("{\"name\":\"原团队\",\"avatarUrl\":\"avatar\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value(71));
        mvc.perform(post("/paiflow/legacy/space-user/page").header("space-id",9).contentType("application/json").content("{\"pageNum\":1,\"pageSize\":10,\"nickname\":\"成员\",\"role\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].uid").value("ruoyi:43")).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.current").value(1)).andExpect(jsonPath("$.data.size").value(10)).andExpect(jsonPath("$.data.pages").value(1));
        var q=org.mockito.ArgumentCaptor.forClass(MembershipService.PageQuery.class);verify(members).memberPage(eq(9L),q.capture());assertEquals(2,q.getValue().role());assertEquals("成员",q.getValue().nickname());
    }
    @Test void invitationArrayScopeAndApplicationQueryPassIntoSingleService() throws Exception {
        var members=mock(MembershipService.class);when(members.invite(eq(1),eq(9L),anyList())).thenReturn(List.of(77L));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new LegacyInvitationController(members),new LegacyApplicationController(members)).build();
        mvc.perform(post("/paiflow/legacy/invite-record/space-invite").header("space-id",9).contentType("application/json").content("[{\"uid\":\"ruoyi:43\",\"role\":2}]"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value("邀请及站内通知已保存"));
        verify(members).invite(eq(1),eq(9L),argThat(values->values.size()==1 && "ruoyi:43".equals(values.get(0).uid()) && values.get(0).role()==2));
        mvc.perform(post("/paiflow/legacy/apply-record/join-enterprise-space").header("enterprise-id",71).param("spaceId","9")).andExpect(status().isOk());
        verify(members).apply(71L,9L);
        mvc.perform(post("/paiflow/legacy/apply-record/agree-enterprise-space").header("space-id",9).param("applyId","77").param("revision","0")).andExpect(status().isOk());
        verify(members).auditApplication(9L,77L,true,0L);
        mvc.perform(post("/paiflow/legacy/invite-record/accept-invite").param("inviteId","77").param("revision","0")).andExpect(status().isOk());verify(members).respondInvitation(77L,true,0L);
    }
}
