package com.ruoyi.paicli.service;
import java.text.Normalizer;
import java.util.*;
/** 从原MemoryDeduplicator/ConflictDetector保留的确定性内容规则，在中心scope内执行。 */
final class PcMemoryFacts {
    private static final String SIGNIFICANT_PUNCTUATION = "#/@\\%_-'’!:&|^~";
    static boolean duplicate(String left,String right){String a=canonicalize(left),b=canonicalize(right);return a.equals(b)||(!a.isEmpty()&&!b.isEmpty()&&isGrammaticalVariant(a,b));}
    static boolean conflict(String left,String right){if(duplicate(left,right))return false;String a=canonicalize(left),b=canonicalize(right);if(a.isEmpty()||b.isEmpty())return false;String x=a.replaceAll("\\d+(?:\\.\\d+)*","\uE000"),y=b.replaceAll("\\d+(?:\\.\\d+)*","\uE000");if(x.contains("\uE000")&&x.equals(y))return true;Map<String,Integer> ag=grams(a),bg=grams(b);int total=ag.values().stream().mapToInt(Integer::intValue).sum()+bg.values().stream().mapToInt(Integer::intValue).sum();int same=0;for(var e:ag.entrySet())same+=Math.min(e.getValue(),bg.getOrDefault(e.getKey(),0));return total>0&&2d*same/total>=0.8d;}
    private static Map<String,Integer> grams(String value){int[] cp=value.codePoints().toArray();Map<String,Integer> result=new HashMap<>();for(int i=0;i+1<cp.length;i++)result.merge(new String(cp,i,2),1,Integer::sum);return result;}
    static String canonicalize(String content) {
        String normalized = Normalizer.normalize(
                content == null ? "" : content,
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);

        int[] codePoints = normalized.codePoints().toArray();
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (Character.isLetterOrDigit(codePoint)) {
                canonical.appendCodePoint(codePoint);
            } else if (isSignificantMarker(codePoints, index)) {
                // 保留原位置，避免 “C++ 和 Java” 与 “C 和 Java++” 产生相同签名。
                canonical.appendCodePoint(codePoint);
            }
        }
        return canonical.toString();
    }

    private static boolean isSignificantMarker(int[] codePoints, int index) {
        int codePoint = codePoints[index];
        int type = Character.getType(codePoint);
        if (type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL) {
            // 包括 +、货币符号和 emoji；这些内容可能就是事实值。
            return true;
        }
        if (codePoint == '.') {
            // 版本号、域名和文件名中的点有意义，句末英文句号没有。
            return hasLetterOrDigitBefore(codePoints, index) && hasLetterOrDigitAfter(codePoints, index);
        }
        if ((codePoint == '\'' || codePoint == '’')
                && !(hasLetterOrDigitBefore(codePoints, index) && hasLetterOrDigitAfter(codePoints, index))) {
            // 普通引号属于格式差异；单词内部撇号才保留。
            return false;
        }
        return SIGNIFICANT_PUNCTUATION.indexOf(codePoint) >= 0;
    }

    private static boolean hasLetterOrDigitBefore(int[] codePoints, int index) {
        return index > 0 && Character.isLetterOrDigit(codePoints[index - 1]);
    }

    private static boolean hasLetterOrDigitAfter(int[] codePoints, int index) {
        return index + 1 < codePoints.length && Character.isLetterOrDigit(codePoints[index + 1]);
    }

    /**
     * 只允许一侧比另一侧多出“的/地/得/是”这类语法助词。
     * 这是有意采用的高精度边界：同义词改写不会被强行合并，更不会把相反或更新后的事实删除。
     */
    private static boolean isGrammaticalVariant(String left, String right) {
        int[] first = left.codePoints().toArray();
        int[] second = right.codePoints().toArray();
        int[] shorter = first.length <= second.length ? first : second;
        int[] longer = first.length <= second.length ? second : first;

        if (shorter.length < 8) {
            return false;
        }
        double containment = (double) shorter.length / longer.length;
        if (containment < 0.82d) {
            return false;
        }

        int shortIndex = 0;
        for (int codePoint : longer) {
            if (shortIndex < shorter.length && codePoint == shorter[shortIndex]) {
                shortIndex++;
                continue;
            }
            if ("的地得是".indexOf(codePoint) < 0) {
                return false;
            }
        }
        return shortIndex == shorter.length;
    }
}
