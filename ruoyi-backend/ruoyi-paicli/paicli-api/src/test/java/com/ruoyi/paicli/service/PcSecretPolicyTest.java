package com.ruoyi.paicli.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PcSecretPolicyTest {
    @Test void detectsNestedHeadersNormalizedKeysCommandArgumentsAndCredentialText(){for(Object value:List.of(Map.of("headers",Map.of("Authorization","Bearer example-token-1234")),Map.of("client_secret","secret"),Map.of("api-secret","secret"),Map.of("access_key","opaque"),Map.of("environment",Map.of("KEY","secret")),List.of("--token","secret"),List.of("--access-key=opaque"),List.of("--secret","opaque"),List.of("--auth-token opaque"),"text pcs_1234567890abcdefghijkl", "Bearer abcdefghijklmnop", "-----BEGIN RSA PRIVATE KEY-----", "eyJabcdefghijk.abcdefghijk.abcdefghijk"))assertTrue(PcSecretPolicy.contains(value),value.toString());}
    @Test void permitsManagedReferencesCountersAndEmptySanitizedFields(){assertFalse(PcSecretPolicy.contains(Map.of("secretRef","ai.paicli.1.provider","tokenCount",5,"dailyTokenBudget",100,"secretConfigured",true,"headers",Map.of("Accept","application/json"),"env",Map.of())));assertFalse(PcSecretPolicy.contains("https://example.com/search?query=ordinary&limit=20"));for(String value:List.of("https://example.com/rpc?access_key=opaque","https://example.com/rpc?%74oken=opaque","https://example.com/rpc?%2574oken=opaque"))assertTrue(PcSecretPolicy.contains(value));}
}
