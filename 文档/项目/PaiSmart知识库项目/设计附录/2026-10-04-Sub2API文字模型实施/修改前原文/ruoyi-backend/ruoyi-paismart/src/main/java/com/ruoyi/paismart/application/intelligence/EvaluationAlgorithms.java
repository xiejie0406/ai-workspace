package com.ruoyi.paismart.application.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.intelligence.Values;
import java.util.*;

/** 原评测口径：任一期望命中、倒数排名、覆盖率、阶段诊断与裁判/人工一致性。 */
public final class EvaluationAlgorithms {
    private EvaluationAlgorithms() { }
    public record Metrics(double recall,double mrr,double coverage,double ndcg) { }
    public record Judge(Integer score,Boolean correct,String reason,String rawOutput,boolean parseFailed) { }
    public enum Diagnosis {
        OK("检索命中，回答正确"), PROMPT_OR_MODEL("检索命中但回答错误：优先检查提示词或模型"),
        RETRIEVAL_MISS("检索未命中：优先检查分块和检索"),
        MISS_BUT_CORRECT("检索未命中但回答正确：检查标注是否完整或是否使用其他文档"),
        NOT_LABELED("没有标注期望文档，只评估回答"), RETRIEVAL_FAILED("检索调用失败"),
        GENERATION_FAILED("生成回答失败"), JUDGE_FAILED("评委调用失败或输出无法解析，需要人工判断");
        public final String description;
        Diagnosis(String description) {this.description=description;}
    }
    public static Diagnosis diagnosis(Boolean hit,boolean answered,Judge judge) {
        boolean judged=judge!=null&&!judge.parseFailed();
        if(Boolean.FALSE.equals(hit))return judged&&Boolean.TRUE.equals(judge.correct())?Diagnosis.MISS_BUT_CORRECT:Diagnosis.RETRIEVAL_MISS;
        if(!answered)return Diagnosis.GENERATION_FAILED;
        if(!judged)return Diagnosis.JUDGE_FAILED;
        if(hit==null)return Diagnosis.NOT_LABELED;
        return Boolean.TRUE.equals(judge.correct())?Diagnosis.OK:Diagnosis.PROMPT_OR_MODEL;
    }
    public static Map<String,String> diagnosisGuide() {Map<String,String> result=new LinkedHashMap<>();for(Diagnosis d:Diagnosis.values())result.put(d.name(),d.description);return result;}
    public static Metrics metrics(List<String> retrieved,Set<String> expected,int topK) {
        if(expected.isEmpty())return new Metrics(0,0,0,0);
        int rank=0;double dcg=0;Set<String> covered=new HashSet<>();
        for(int i=0;i<Math.min(topK,retrieved.size());i++)if(expected.contains(retrieved.get(i))){if(rank==0)rank=i+1;if(covered.add(retrieved.get(i)))dcg+=1/(Math.log(i+2)/Math.log(2));}
        double ideal=0;for(int i=0;i<Math.min(topK,expected.size());i++)ideal+=1/(Math.log(i+2)/Math.log(2));
        return new Metrics(rank>0?1:0,rank>0?1.0/rank:0,(double)covered.size()/expected.size(),ideal==0?0:dcg/ideal);
    }
    public static Judge parseJudge(String content) {
        String raw=truncate(content,500);
        if(content==null||content.isBlank())return new Judge(null,null,"评委输出为空",raw,true);
        int start=content.indexOf('{'),end=content.lastIndexOf('}');
        if(start<0||end<=start)return new Judge(null,null,"评委输出中没有JSON对象",raw,true);
        try {
            Map<String,Object> node=Jsons.map(content.substring(start,end+1));Integer score=score(node.get("score"));Boolean correct=bool(node.get("correct"));
            if(score==null)return new Judge(null,null,"score缺失或不是1到5的整数",raw,true);
            if(correct==null)return new Judge(null,null,"correct缺失或不是布尔值",raw,true);
            return new Judge(score,correct,truncate(Values.text(node.get("reason")),300),raw,false);
        } catch(RuntimeException invalid) {return new Judge(null,null,"评委输出不是合法JSON对象",raw,true);}
    }
    private static Integer score(Object input) {try {if(!(input instanceof Number)&&!(input instanceof String))return null;double n=Double.parseDouble(input.toString().trim());return Double.isFinite(n)&&n==Math.rint(n)&&n>=1&&n<=5?(int)n:null;}catch(RuntimeException invalid){return null;}}
    private static Boolean bool(Object input) {if(input instanceof Boolean b)return b;if(input instanceof String s){if(s.trim().equalsIgnoreCase("true"))return true;if(s.trim().equalsIgnoreCase("false"))return false;}return null;}
    public static String truncate(String value,int max) {return value==null?null:value.length()<=max?value:value.substring(0,max)+"…";}
    public static List<Map<String,Object>> dataset(String jsonl) {
        List<Map<String,Object>> rows=new ArrayList<>();Set<String> ids=new HashSet<>();int lineNo=0;
        for(String line:jsonl.split("\\R",-1)) {
            lineNo++;String text=line.startsWith("\uFEFF")?line.substring(1):line;text=text.trim();
            if(text.isEmpty()||text.startsWith("#")||text.startsWith("//"))continue;
            if(rows.size()>=500)throw new IllegalArgumentException("评测最多500样本");
            Map<String,Object> row;try {row=new LinkedHashMap<>(Jsons.map(text));}catch(RuntimeException invalid){throw new IllegalArgumentException("第"+lineNo+"行不是合法JSON对象");}
            String id=Values.text(row.get("id")).trim();if(id.isBlank())id="line-"+lineNo;
            if(id.length()>100||!ids.add(id))throw new IllegalArgumentException("第"+lineNo+"行编号过长或重复");row.put("id",id);
            if(!row.containsKey("question"))row.put("question",row.get("query"));
            try {row.put("question",Values.required(row,"question",4000));row.put("referenceAnswer",Values.required(row,"referenceAnswer",12000));}catch(IllegalArgumentException missing){throw new IllegalArgumentException("第"+lineNo+"行："+missing.getMessage());}
            if(!strings(row.get("expectedFileMd5s")).isEmpty())throw new IllegalArgumentException("第"+lineNo+"行旧MD5需按导入映射转换为expectedDocumentIds");
            row.put("expectedDocumentIds",strings(row.get("expectedDocumentIds")));row.put("expectedFileNames",strings(row.get("expectedFileNames")));rows.add(row);
        }
        if(rows.isEmpty())throw new IllegalArgumentException("评测集为空");return rows;
    }
    public static List<String> strings(Object input) {if(input==null)return List.of();List<?> list=input instanceof String s?List.of(s):input instanceof List<?> l?l:null;if(list==null)throw new IllegalArgumentException("期望文档须为字符串或字符串数组");List<String> out=new ArrayList<>();for(Object item:list){if(!(item instanceof String))throw new IllegalArgumentException("期望文档须为字符串");String s=item.toString().trim();if(!s.isEmpty()){if(s.length()>255||out.size()>=100)throw new IllegalArgumentException("期望文档列表超限");out.add(s);}}return out;}
    public static Map<String,Object> summarize(List<Map<String,Object>> cases) {
        int labeled=0,hits=0,judged=0,correct=0,review=0,failed=0;double rr=0,coverage=0,ndcg=0,score=0;
        Map<String,Long> modes=new TreeMap<>(),diagnoses=new LinkedHashMap<>();for(Diagnosis d:Diagnosis.values())diagnoses.put(d.name(),0L);
        for(var c:cases) {
            if(c.get("retrievalHit") instanceof Boolean hit){labeled++;if(hit)hits++;rr+=numeric(c.get("reciprocalRank"));coverage+=numeric(c.get("expectedDocCoverage"));ndcg+=numeric(c.get("ndcg"));}
            if(c.get("judgeScore") instanceof Number n&&c.get("judgeCorrect") instanceof Boolean b){judged++;score+=n.doubleValue();if(b)correct++;}
            if(Boolean.TRUE.equals(c.get("needsHumanReview")))review++;
            String diagnosis=Values.text(c.get("diagnosis"));if(Set.of("RETRIEVAL_FAILED","GENERATION_FAILED","JUDGE_FAILED").contains(diagnosis))failed++;
            if(diagnoses.containsKey(diagnosis))diagnoses.merge(diagnosis,1L,Long::sum);
            String mode=Values.text(c.get("retrievalMode"));modes.merge(mode.isBlank()?"NONE":mode,1L,Long::sum);
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("totalCases",cases.size());out.put("labeledCases",labeled);out.put("retrievalHits",hits);out.put("recallAtK",ratio(hits,labeled));out.put("mrr",ratio(rr,labeled));out.put("meanExpectedDocCoverage",ratio(coverage,labeled));out.put("ndcg",ratio(ndcg,labeled));out.put("judgedCases",judged);out.put("correctCases",correct);out.put("meanJudgeScore",ratio(score,judged));out.put("accuracy",ratio(correct,judged));out.put("needsHumanReviewCount",review);out.put("failedCases",failed);out.put("retrievalModeCounts",modes);out.put("diagnosisCounts",diagnoses);
        out.put("samples",cases.size());out.put("recall",out.get("recallAtK"));out.put("humanReviewNeeded",review);return out;
    }
    private static double numeric(Object value){return value instanceof Number n?n.doubleValue():0;}
    private static Double ratio(double numerator,int denominator){return denominator==0?null:Math.round(numerator/denominator*10000d)/10000d;}
    public static Map<String,Object> agreement(List<Map<String,Object>> pairs,int minComparable,double threshold) {return agreement(pairs,minComparable,threshold,null);}
    public static Map<String,Object> agreement(List<Map<String,Object>> pairs,int minComparable,double threshold,Double minKappa) {
        if(minComparable<1||!Double.isFinite(threshold)||threshold<0||threshold>1||minKappa!=null&&(!Double.isFinite(minKappa)||minKappa< -1||minKappa>1))throw new IllegalArgumentException("人工一致性阈值无效");
        int n=0,agree=0,jyes=0,hyes=0,scored=0,humanOnly=0;double diff=0;List<String> disagreements=new ArrayList<>();
        for(var p:pairs) {if(!(p.get("human") instanceof Boolean h))continue;if(!(p.get("judge") instanceof Boolean j)){humanOnly++;continue;}n++;if(j==h)agree++;else if(disagreements.size()<50)disagreements.add(Values.text(p.get("caseId")));if(j)jyes++;if(h)hyes++;Integer js=score(p.get("judgeScore")),hs=score(p.get("humanScore"));if(js!=null&&hs!=null){scored++;diff+=Math.abs(js-hs);}}
        Double rate=n==0?null:(double)agree/n;double expected=n==0?1:(jyes/(double)n)*(hyes/(double)n)+(1-jyes/(double)n)*(1-hyes/(double)n);Double kappa=n==0||Math.abs(1-expected)<1e-12?null:(rate-expected)/(1-expected);
        String status=pairs.isEmpty()?"NO_REVIEWS":n<minComparable?"INSUFFICIENT_SAMPLE":rate<threshold||minKappa!=null&&kappa!=null&&kappa<minKappa?"NEEDS_RECALIBRATION":"OK";
        String warning=switch(status){case "INSUFFICIENT_SAMPLE"->"评委和人工都给出结论的用例只有"+n+"条（至少需要 "+minComparable+" 条），一致率暂不下结论";case "NEEDS_RECALIBRATION"->String.format(Locale.ROOT,"评委与人工一致率 %.1f%%（阈值 %.1f%%），Cohen's kappa %s%s。需要重新校准；本次准确率和平均分不能直接采信，请检查分歧用例",rate*100,threshold*100,kappa==null?"无定义":String.format(Locale.ROOT,"%.2f",kappa),minKappa==null?"":"（下限 "+minKappa+"）");default->null;};
        Map<String,Object> out=new LinkedHashMap<>();out.put("status",status);out.put("warning",warning);out.put("reviewedCases",pairs.size());out.put("comparableCases",n);out.put("agreedCases",agree);out.put("agreementRate",rate);out.put("cohensKappa",kappa);out.put("judgeCorrectRate",n==0?null:(double)jyes/n);out.put("humanCorrectRate",n==0?null:(double)hyes/n);out.put("scoredCases",scored);out.put("meanAbsoluteScoreDiff",scored==0?null:diff/scored);out.put("humanOnlyCases",humanOnly);out.put("disagreementCaseIds",disagreements);out.put("minAgreementRate",threshold);out.put("minKappa",minKappa);out.put("minComparableCases",minComparable);return out;
    }
}
