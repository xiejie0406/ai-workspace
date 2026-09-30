package com.ruoyi.fashion.application.pool;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.infrastructure.persistence.pool.JdbcFashionPoolRepository;
class JdbcFashionPoolRepositoryTest {
 @Test void excessiveSnapshotIsRejectedBeforeDatabaseWrite(){var jdbc=mock(NamedParameterJdbcTemplate.class);var mapper=mock(ObjectMapper.class);var pool=PoolCombinationsTest.pool(List.of(PoolCombinationsTest.group("a",1)),null,null);when(mapper.writeValueAsString(pool)).thenReturn("x".repeat(20*1024*1024+1));var repository=new JdbcFashionPoolRepository(jdbc,mapper);assertThrows(ServiceException.class,()->repository.insert(pool,1));verifyNoInteractions(jdbc);}
}
