package com.paicli.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ManagedProviderPersonalPurposeTest {
    private ObjectNode claim(){return CanonicalJson.MAPPER.createObjectNode().put("ownerUserId","7").put("taskId","1").put("leaseId","2").put("fencingToken",1).put("version",1).put("policyVersion",1).put("rootFingerprint","a".repeat(64)).put("expiresAt",Instant.now().plusSeconds(120).toString());}
    private ObjectNode context(String owner){
        ObjectNode value=CanonicalJson.MAPPER.createObjectNode();value.putObject("providerConfig").put("ownerUserId",owner);value.putObject("modelConfig").put("ownerUserId",owner);
        value.putObject("managedSecret").put("secretRef","ai.paicli.user."+owner+".provider.1").put("apiKey","fixture-managed").put("expiresAt",Instant.now().plusSeconds(50).toString());return value;
    }
    @Test void personalPurposeRejectsAnotherOwnerWithoutFrozenRelease()throws Exception{
        try(var center=new CenterHttpClient("http://127.0.0.1:1","fixture",true)){
            assertEquals("fixture-managed",new ManagedProviderGrant(center,new RunnerLease(claim()),context("7"),"managedSecret").currentKey());
            assertThrows(IOException.class,()->new ManagedProviderGrant(center,new RunnerLease(claim()),context("8"),"managedSecret").currentKey());
        }
    }
    @Test void explicitlyPublishedModelKeepsSourceOwnerWithoutChangingTaskOwner()throws Exception{
        ObjectNode claim=claim();claim.putObject("payload").put("modelReleaseId","9").put("modelReleaseVersion",4).put("modelSecretOwnerId","8");
        try(var center=new CenterHttpClient("http://127.0.0.1:1","fixture",true)){
            RunnerLease lease=new RunnerLease(claim);assertEquals("fixture-managed",new ManagedProviderGrant(center,lease,context("8"),"managedSecret").currentKey());assertEquals("7",lease.identity().path("ownerUserId").asText());
            claim.withObject("/payload").remove("modelReleaseVersion");assertThrows(IOException.class,()->new ManagedProviderGrant(center,new RunnerLease(claim),context("8"),"managedSecret").currentKey());
        }
    }
}
