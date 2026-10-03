package com.ruoyi.paismart.application.intelligence;
import com.ruoyi.paismart.infrastructure.intelligence.Values;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValuesBoundaryTest {
    @Test void realIntegersAndSameValueDecimalRemainCompatible(){for(Object value:List.of(1,1L,1.0,new BigDecimal("1.00"),new BigInteger("1"),"1","+1"))assertEquals(1,Values.number(value,-1));assertEquals(-1,Values.number(null,-1));}
    @Test void fractionalNonfiniteAndOverflowValuesCannotBeSilentlyTruncated(){for(Object value:List.of(1.5,-1.2,new BigDecimal("4.5"),Double.NaN,Double.POSITIVE_INFINITY,new BigInteger("9223372036854775808"),"9223372036854775808"))assertThrows(IllegalArgumentException.class,()->Values.number(value,0));}
    @Test void stringParsingKeepsExistingLongSemantics(){for(String value:List.of("1.0"," 1 ","true","","0x10"))assertThrows(IllegalArgumentException.class,()->Values.number(value,0));assertEquals(Long.MAX_VALUE,Values.number(Long.toString(Long.MAX_VALUE),0));}
    @Test void topKAndHumanScoreRequireExactIntegerBeforeRangeChecks(){assertThrows(IllegalArgumentException.class,()->Values.range(4.5,5,1,20));assertThrows(IllegalArgumentException.class,()->Values.range(new BigDecimal("4.5"),0,1,5));assertEquals(4,Values.range(4.0,0,1,5));}
    @Test void fractionalModelClaimIdCannotAliasAnActualCitationVerdict(){var pairs=List.of(new CitationService.ClaimPair(1,"事实",1,"证据"));assertThrows(IllegalArgumentException.class,()->CitationService.aggregate(pairs,"[{\"id\":1.9,\"supported\":true}]"));}
}
