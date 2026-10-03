package com.ruoyi.paicli.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PcMemoryFactsTest {
    @Test void formattingDeduplicatesButNumbersRemainConflicts(){assertTrue(PcMemoryFacts.duplicate("项目用 Java 17。","项目用ＪＡＶＡ１７"));assertFalse(PcMemoryFacts.duplicate("项目用 Java 17","项目用 Java 21"));assertTrue(PcMemoryFacts.conflict("项目用 Java 17","项目用 Java 21"));}
    @Test void codeSymbolsAndDifferentFactsDoNotCollapse(){assertFalse(PcMemoryFacts.duplicate("C++ 和 Java","C 和 Java++"));assertFalse(PcMemoryFacts.conflict("每天北京时间开始工作","工作区使用Node运行"));}
}
