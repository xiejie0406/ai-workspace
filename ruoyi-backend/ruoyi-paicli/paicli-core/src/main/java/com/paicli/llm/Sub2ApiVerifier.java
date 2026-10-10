package com.paicli.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.paicli.llm.AbstractOpenAiCompatibleClient.mapper;

/** 两次有预算的合成请求，工具仅在内存回显，绝不调用实际业务工具。 */
public final class Sub2ApiVerifier {
    private Sub2ApiVerifier() {}
    public static Map<String,Object> verify(LlmClient client) throws IOException {
        var parameters=mapper.readTree("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"enum\":[\"probe\"]}},\"required\":[\"value\"],\"additionalProperties\":false}");
        var tool=new LlmClient.Tool("paicli_probe_echo","仅在验证器内存中回显probe，不执行业务工具",parameters);
        List<LlmClient.Message> history=new ArrayList<>(List.of(LlmClient.Message.system(
                "This is a compatibility probe. First call paicli_probe_echo with value probe exactly once. After the tool result, reply with exactly probe and do not call another tool."),LlmClient.Message.user("Run the probe.")));
        LlmClient.ChatResponse first=client.chat(history,List.of(tool));
        if(!first.usagePresent()||first.toolCalls()==null||first.toolCalls().size()!=1)throw new IOException("Sub2API工具/用量验证失败");
        LlmClient.ToolCall call=first.toolCalls().get(0);var args=mapper.readTree(call.function().arguments());
        if(!tool.name().equals(call.function().name())||args==null||args.size()!=1||!"probe".equals(args.path("value").asText()))throw new IOException("Sub2API工具参数验证失败");
        history.add(LlmClient.Message.assistant(first));history.add(LlmClient.Message.tool(call.id(),"probe"));
        AtomicBoolean streamed=new AtomicBoolean();
        LlmClient.ChatResponse second=client.chat(history,List.of(tool),new LlmClient.StreamListener(){
            public void onContentDelta(String delta){if(delta!=null&&!delta.isEmpty())streamed.set(true);}
        });
        if(!second.usagePresent()||second.hasToolCalls()||!"probe".equals(second.content().trim())||!streamed.get())throw new IOException("Sub2API文本/流式/工具回灌验证失败");
        return Map.of("text",true,"tools",true,"stream",true,"usage",true,"model",client.getModelName(),"calls",2);
    }

    /** 独立图片探针；答案仅在图片与本地预期中，工具schema/问题不泄漏正确答案。 */
    public static Map<String,Object> verifyImages(LlmClient client) throws IOException {
        if (!client.supportsImageInput()) throw new IOException("当前验证客户端未获图片输入能力");
        byte[] sample=imageSample();
        String sampleHash;
        try { sampleHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sample)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var expected=mapper.readTree("{\"token\":\"VISION-4827\",\"count\":2,\"leftColor\":\"blue\",\"leftShape\":\"square\",\"leftNumber\":7,\"rightColor\":\"red\",\"rightShape\":\"circle\",\"rightNumber\":3}");
        var schema=mapper.createObjectNode().put("type","object");
        var properties=schema.putObject("properties"); var required=schema.putArray("required");
        expected.fieldNames().forEachRemaining(field->{properties.putObject(field).put("type",expected.get(field).isIntegralNumber()?"integer":"string");required.add(field);});
        schema.put("additionalProperties",false);
        var tool=new LlmClient.Tool("paicli_image_probe_echo","仅在验证器内存中回显本次图片观察，不执行业务工具",schema);
        List<LlmClient.Message> history=new ArrayList<>(List.of(LlmClient.Message.system(
                "Inspect the attached image. Read the text token, number of shapes, and each shape's color, shape and number from left to right. "
                +"Use lowercase English color and shape names. Call paicli_image_probe_echo exactly once with your observations. "
                +"After the tool result, reply with exactly the tool result JSON and do not call another tool."),
                LlmClient.Message.user(List.of(LlmClient.ContentPart.text("Inspect this image and run the image probe."),
                        LlmClient.ContentPart.imageBase64(Base64.getEncoder().encodeToString(sample),"image/png")))));
        var first=client.chat(history,List.of(tool));
        if (!first.usagePresent() || first.toolCalls()==null || first.toolCalls().size()!=1) throw new IOException("Sub2API图片工具或用量验证失败");
        var call=first.toolCalls().get(0);
        var observed=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.function().arguments());
        if (!tool.name().equals(call.function().name()) || !expected.equals(observed)) throw new IOException("Sub2API图片独立预期核对失败");
        history.add(LlmClient.Message.assistant(first)); history.add(LlmClient.Message.tool(call.id(),observed.toString()));
        AtomicBoolean streamed=new AtomicBoolean();
        var second=client.chat(history,List.of(tool),new LlmClient.StreamListener(){
            public void onContentDelta(String delta){if(delta!=null&&!delta.isEmpty())streamed.set(true);}
        });
        if (!second.usagePresent() || second.hasToolCalls() || !streamed.get()) throw new IOException("Sub2API图片流式回灌验证失败");
        var finalResult=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(second.content());
        if (!expected.equals(finalResult)) throw new IOException("Sub2API图片最终回灌结果不符");
        Map<String,Object> proof=new LinkedHashMap<>();
        proof.put("images",true); proof.put("imageTools",true); proof.put("imageStream",true); proof.put("imageUsage",true);
        proof.put("imageSampleSha256",sampleHash); proof.put("imageSampleVersion","paicli-vision-v1");
        proof.put("imageChecks",8); proof.put("imageCalls",2); proof.put("model",client.getModelName());
        return Map.copyOf(proof);
    }

    private static byte[] imageSample() throws IOException {
        var image=new java.awt.image.BufferedImage(640,320,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0,0,640,320);
            graphics.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,java.awt.Font.BOLD,36));
            graphics.setColor(java.awt.Color.BLACK); graphics.drawString("VISION-4827",170,55);
            graphics.setColor(java.awt.Color.BLUE); graphics.fillRect(100,105,140,140);
            graphics.setColor(java.awt.Color.RED); graphics.fillOval(390,105,140,140);
            graphics.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,java.awt.Font.BOLD,60));
            graphics.setColor(java.awt.Color.WHITE); graphics.drawString("7",151,195); graphics.drawString("3",441,195);
        } finally { graphics.dispose(); }
        var bytes=new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image,"png",bytes)) throw new IOException("图片验证样本编码失败");
        return bytes.toByteArray();
    }
}
