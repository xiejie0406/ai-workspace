package com.ruoyi.paismart.application.intelligence;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IntelligenceAlgorithmsTest {
    @Test void rrfDoesNotDoubleCountRepeatedHitInOnePath(){var result=RrfFusion.fuse(60,(String s)->s,List.of("a","a","b"),List.of("b","a"));assertEquals("a",result.get(0).key());assertEquals(1.0/61+1.0/62,result.get(0).score(),1e-12);assertArrayEquals(new int[]{1,2},result.get(0).ranks());}
    @Test void numbersOnlyConflictWhenFactsMatch(){assertTrue(KeyValueConflictAnalyzer.analyze("每月费用为100元。","每月费用为200元。",0.8).conflict());assertFalse(KeyValueConflictAnalyzer.analyze("每月费用为100元。","每月费用为１００元。",0.8).conflict());assertFalse(KeyValueConflictAnalyzer.analyze("食堂人数100人。","工程费用200万元。",0.8).conflict());}
    @Test void retrievalMetricsIndependentKnownExpected(){var result=EvaluationAlgorithms.metrics(List.of("wrong","right","right","other"),Set.of("right","other"),4);assertEquals(1,result.recall());assertEquals(0.5,result.mrr());assertEquals(1,result.coverage());double dcg=1/(Math.log(3)/Math.log(2))+1/(Math.log(5)/Math.log(2)),ideal=1+1/(Math.log(3)/Math.log(2));assertEquals(dcg/ideal,result.ndcg(),1e-12);}
    @Test void agreementDoesNotClaimReliableWhenOnlyOneSample(){var result=EvaluationAlgorithms.agreement(List.of(Map.of("judge",true,"human",true)),5,.8);assertEquals("INSUFFICIENT_SAMPLE",result.get("status"));assertNull(result.get("cohensKappa"));}
    @Test void deterministicSamplingNotShiftedByJudgeFailure(){var a=HumanReviewSampler.of(.4,42L);var b=HumanReviewSampler.of(.4,42L);assertEquals("JUDGE_FAILED",a.decide(true));b.decide(false);for(int i=0;i<20;i++)assertEquals(a.decide(false),b.decide(false));}
    @Test void chunkSplittingPreservesSurrogatePairsAndTail(){String text="甲乙丙丁。".repeat(40)+"😀结尾";var chunks=TextChunker.split(text,60,10,20);assertTrue(chunks.size()>2);assertTrue(chunks.get(chunks.size()-1).endsWith("😀结尾"));for(String chunk:chunks){assertFalse(Character.isLowSurrogate(chunk.charAt(0)));assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length()-1)));}}
}
