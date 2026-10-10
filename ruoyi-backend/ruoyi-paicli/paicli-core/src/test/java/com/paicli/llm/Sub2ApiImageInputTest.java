package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.config.PaiCliConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static com.paicli.llm.AbstractOpenAiCompatibleClient.mapper;
import static org.junit.jupiter.api.Assertions.*;

/** 仅本机合成HTTP/SSE与真实小PNG，核对图片传输和独立验证边界。 */
class Sub2ApiImageInputTest {
    private static final String OBSERVATIONS="{\"token\":\"VISION-4827\",\"count\":2,\"leftColor\":\"blue\",\"leftShape\":\"square\",\"leftNumber\":7,\"rightColor\":\"red\",\"rightShape\":\"circle\",\"rightNumber\":3}";
    private static final String USAGE="{\"input_tokens\":10,\"output_tokens\":3}";
    private static ModelProfile imageProfile(Boolean enabled) { return new ModelProfile(null,128000,enabled,null,"low",false,false,8192,"fixture"); }
    private static String png() throws IOException {
        var image=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,0x123456);
        var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private static List<LlmClient.Message> imageMessages() throws IOException {
        return List.of(LlmClient.Message.user(List.of(LlmClient.ContentPart.text("before"),
                LlmClient.ContentPart.imageBase64(png(),"image/png"),LlmClient.ContentPart.text("after"))));
    }
    private static PaiCliConfig config(MockWebServer server,String protocol,Boolean enabled) {
        var config=new PaiCliConfig(); config.setDefaultProvider("sub2api");
        var provider=new PaiCliConfig.ProviderConfig("synthetic-key",server.url("/v1").toString(),"fixture-model");
        provider.setWireApi(protocol); provider.getModels().put("fixture-model",protocol.equals("responses")?imageProfile(enabled)
                :new ModelProfile(null,128000,enabled,null,null,false,false,8192,"fixture"));
        config.getProviders().put("sub2api",provider);return config;
    }
    private static String response(JsonNode output) { return "{\"status\":\"completed\",\"model\":\"fixture-model\",\"output\":["+output+"],\"usage\":"+USAGE+"}"; }
    private static JsonNode text(String value) { var item=mapper.createObjectNode().put("type","message").put("role","assistant").put("status","completed");item.putArray("content").addObject().put("type","output_text").put("text",value);return item; }
    private static String event(String type,String content) { return "data: {\"type\":\""+type+"\","+content+"}\n\n"; }

    @Test void responsesTransmitsOrderedRealImageBlocksWithoutPlaceholderDuplication() throws Exception {
        try(var server=new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(response(text("done"))));
            var client=LlmClientFactory.createFromConfig(config(server,"responses",true));assertTrue(client.supportsImageInput());
            assertEquals("done",client.chat(imageMessages(),null).content());
            var request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);assertEquals("/v1/responses",request.getPath());
            var body=mapper.readTree(request.getBody().readUtf8());var blocks=body.path("input").get(0).path("content");
            assertEquals(3,blocks.size());assertEquals("before",blocks.get(0).path("text").asText());
            assertEquals("input_image",blocks.get(1).path("type").asText());
            assertEquals("data:image/png;base64,"+png(),blocks.get(1).path("image_url").asText());
            assertEquals("after",blocks.get(2).path("text").asText());assertFalse(body.toString().contains("已附加"));
            assertTrue(body.path("stream").asBoolean());assertFalse(body.path("store").asBoolean());
        }
    }

    @Test void undeclaredAndDisabledResponsesNeverSendImage() throws Exception {
        try(var server=new MockWebServer()) {
            for(Boolean enabled:new Boolean[]{null,false}) {
                var client=LlmClientFactory.createFromConfig(config(server,"responses",enabled));assertFalse(client.supportsImageInput());
                assertThrows(IOException.class,()->client.chat(imageMessages(),null));
            }
            assertEquals(0,server.getRequestCount());
        }
    }

    @Test void chatCompletionsCannotBeEnabledByImageProfile() throws Exception {
        try(var server=new MockWebServer()) {
            assertThrows(IllegalArgumentException.class,()->LlmClientFactory.createFromConfig(config(server,"chat_completions",true)));
            var client=LlmClientFactory.createFromConfig(config(server,"chat_completions",false));assertFalse(client.supportsImageInput());
            assertThrows(IOException.class,()->client.chat(imageMessages(),null));assertEquals(0,server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"assistant","tool","system","developer"})
    void onlyUserCanSupplyImageBlocks(String role) throws Exception {
        var message=new LlmClient.Message(role,"",null,null,"call",List.of(LlmClient.ContentPart.imageBase64(png(),"image/png")));
        assertThrows(IOException.class,()->Sub2ApiResponsesCodec.request("fixture",List.of(message),null,imageProfile(true)));
    }

    @Test void remotePathsBrokenBase64MimeSpoofAndUnsupportedKindsFailLocally() throws Exception {
        var invalid=List.of(LlmClient.ContentPart.imageUrl("https://example.com/image.png"),
                LlmClient.ContentPart.imageUrl("C:/private.png"),LlmClient.ContentPart.imageBase64("%%%","image/png"),
                LlmClient.ContentPart.imageBase64(png(),"image/jpeg"),LlmClient.ContentPart.imageBase64(png(),"image/gif"),
                LlmClient.ContentPart.imageBase64(Base64.getEncoder().encodeToString("not an image".getBytes()),"image/png"),
                new LlmClient.ContentPart("image_artifact",null,null,null,"image/png"));
        for(var part:invalid) assertThrows(IOException.class,()->Sub2ApiResponsesCodec.request("fixture",List.of(LlmClient.Message.user(List.of(part))),null,imageProfile(true)));
        assertThrows(IOException.class,()->Sub2ApiResponsesCodec.request("fixture",List.of(LlmClient.Message.user(java.util.Collections.nCopies(11,LlmClient.ContentPart.imageBase64(png(),"image/png")))),null,imageProfile(true)));
    }

    @Test void imageContinuationKeepsEncryptedProtocolHistoryAndToolReceipt() throws Exception {
        var assistant=mapper.readTree("{\"type\":\"reasoning\",\"encrypted_content\":\"opaque-history\"}");
        var history=new ArrayList<>(imageMessages());
        history.add(new LlmClient.Message("assistant","",null,null,null,null,List.of(assistant)));
        history.add(LlmClient.Message.tool("call-image","actual-result"));
        var body=Sub2ApiResponsesCodec.request("fixture",history,null,imageProfile(true));
        assertEquals("input_image",body.path("input").get(0).path("content").get(1).path("type").asText());
        assertEquals("opaque-history",body.path("input").get(1).path("encrypted_content").asText());
        assertEquals("call-image",body.path("input").get(2).path("call_id").asText());
        assertEquals("actual-result",body.path("input").get(2).path("output").asText());
    }

    @Test void imageStreamWithoutRealTerminalDoesNotReplay() throws Exception {
        try(var server=new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody(event("response.output_text.delta","\"delta\":\"partial\"")));
            var client=LlmClientFactory.createFromConfig(config(server,"responses",true));
            assertThrows(IOException.class,()->client.chat(imageMessages(),null));assertEquals(1,server.getRequestCount());
        }
    }

    @Test void independentImageProbeIncludesPictureToolResultStreamAndUsage() throws Exception {
        try(var server=new MockWebServer()) {
            var call=mapper.createObjectNode().put("type","function_call").put("status","completed").put("call_id","call-image")
                    .put("name","paicli_image_probe_echo").put("arguments",OBSERVATIONS);
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(response(call)));
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody(
                    event("response.output_text.delta","\"delta\":"+mapper.writeValueAsString(OBSERVATIONS))
                    +event("response.completed","\"response\":"+response(text(OBSERVATIONS)))));
            var proof=Sub2ApiVerifier.verifyImages(LlmClientFactory.createFromConfig(config(server,"responses",true)));
            assertEquals(true,proof.get("images"));assertEquals(true,proof.get("imageTools"));assertEquals(8,proof.get("imageChecks"));
            assertEquals(2,proof.get("imageCalls"));assertTrue(proof.get("imageSampleSha256").toString().matches("[a-f0-9]{64}"));
            var first=mapper.readTree(server.takeRequest().getBody().readUtf8());var second=mapper.readTree(server.takeRequest().getBody().readUtf8());
            assertTrue(first.toString().contains("input_image"));assertFalse(first.toString().contains("VISION-4827"));
            assertFalse(first.path("tools").toString().contains("blue"));
            assertTrue(second.toString().contains("function_call_output"));assertTrue(second.toString().contains("call-image"));
            assertEquals(2,server.getRequestCount());
        }
    }

    @Test void wrongVisualObservationCannotBecomeProofOrTriggerSecondCall() throws Exception {
        try(var server=new MockWebServer()) {
            var call=mapper.createObjectNode().put("type","function_call").put("status","completed").put("call_id","call-image")
                    .put("name","paicli_image_probe_echo").put("arguments",OBSERVATIONS.replace("4827","0000"));
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(response(call)));
            assertThrows(IOException.class,()->Sub2ApiVerifier.verifyImages(LlmClientFactory.createFromConfig(config(server,"responses",true))));
            assertEquals(1,server.getRequestCount());
        }
    }

    @Test void unknownImageCapabilityCannotStartProbe() throws Exception {
        try(var server=new MockWebServer()) {
            assertThrows(IOException.class,()->Sub2ApiVerifier.verifyImages(LlmClientFactory.createFromConfig(config(server,"responses",null))));
            assertEquals(0,server.getRequestCount());
        }
    }

    @Test void imageProbeWithoutFinalUsageCannotBecomeEvidence() throws Exception {
        try(var server=new MockWebServer()) {
            var call=mapper.createObjectNode().put("type","function_call").put("status","completed").put("call_id","call-image")
                    .put("name","paicli_image_probe_echo").put("arguments",OBSERVATIONS);
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(response(call)));
            String unknownUsage="{\"status\":\"completed\",\"model\":\"fixture-model\",\"output\":["+text(OBSERVATIONS)+"]}";
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBody(
                    event("response.output_text.delta","\"delta\":"+mapper.writeValueAsString(OBSERVATIONS))
                    +event("response.completed","\"response\":"+unknownUsage)));
            assertThrows(IOException.class,()->Sub2ApiVerifier.verifyImages(LlmClientFactory.createFromConfig(config(server,"responses",true))));
            assertEquals(2,server.getRequestCount());
        }
    }
}
