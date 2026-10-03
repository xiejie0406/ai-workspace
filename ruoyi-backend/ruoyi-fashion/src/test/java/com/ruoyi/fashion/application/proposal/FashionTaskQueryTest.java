package com.ruoyi.fashion.application.proposal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
class FashionTaskQueryTest {
    @Test void runPermissionCannotReadPoolImageTasksOrQuoteLinkedRuns(){var jdbc=mock(NamedParameterJdbcTemplate.class);when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(0L);when(jdbc.queryForList(anyString(),anyMap())).thenReturn(new ArrayList<>());new FashionTaskQuery(jdbc).list(7,true,false,1,20);verify(jdbc).queryForList(argThat(sql->sql.contains("r.quote_id is null")&&!sql.contains("fq_proposal_image_task")&&!sql.contains("input_json")),org.mockito.ArgumentMatchers.<Map<String,Object>>argThat(p->p.get("owner").equals(7L)));}
    @Test void noSourcePermissionReturnsEmptyWithoutQuery(){var jdbc=mock(NamedParameterJdbcTemplate.class);assertEquals(0,new FashionTaskQuery(jdbc).list(7,false,false,1,20).get("total"));verifyNoInteractions(jdbc);}
}

