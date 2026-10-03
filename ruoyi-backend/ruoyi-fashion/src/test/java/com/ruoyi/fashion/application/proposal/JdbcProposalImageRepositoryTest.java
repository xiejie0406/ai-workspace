package com.ruoyi.fashion.application.proposal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.fashion.infrastructure.persistence.image.JdbcProposalImageRepository;
class JdbcProposalImageRepositoryTest {
    @Test void acceptedUnknownCostCheckDoesNotUseMonthlyWindowOrCoalesceUnknownToZero(){var jdbc=mock(NamedParameterJdbcTemplate.class);when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(1L);assertTrue(new JdbcProposalImageRepository(jdbc,new ObjectMapper()).hasUnpricedAcceptedTasks());verify(jdbc).queryForObject(argThat(sql->sql.contains("provider_task_id is not null")&&sql.contains("estimated_cost is null")&&sql.contains("cost_currency is null")&&!sql.contains("coalesce")&&!sql.contains("create_time")),anyMap(),eq(Long.class));}
    @Test void noUnpricedAcceptedTaskDoesNotBlockPaidBudgetCheck(){var jdbc=mock(NamedParameterJdbcTemplate.class);when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(0L);assertFalse(new JdbcProposalImageRepository(jdbc,new ObjectMapper()).hasUnpricedAcceptedTasks());}
}
