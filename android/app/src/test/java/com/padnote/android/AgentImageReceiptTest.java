package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Shared C39 vectors for portable image identity and receipt bytes. */
public final class AgentImageReceiptTest {
    private JSONObject fixture() throws Exception {
        try(InputStream in=getClass().getClassLoader().getResourceAsStream("AGENT_IMAGE_RECEIPT_CASES_R1.json")){
            assertNotNull("shared image-receipt fixture must be copied byte-for-byte",in);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))>=0)if(n>0)out.write(b,0,n);
            return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
    }

    @Test public void sharedIdentityAndCanonicalReceiptMatchGoldenBytes() throws Exception {
        JSONObject root=fixture(),note=root.getJSONObject("validSchema11");
        JSONObject image=note.getJSONArray("images").getJSONObject(0);
        AgentImageReceipt receipt=AgentImageReceipt.fromJson(image.getJSONObject("agentImage"));
        assertEquals(root.getString("identitySHA256"),receipt.applicationId);
        assertEquals(root.getString("canonicalReceiptHex"),hex(receipt.canonicalBytes()));
        byte[] png=java.util.Base64.getDecoder().decode(root.getString("pngBytesBase64"));
        receipt.validatePng(png);
        assertEquals("agent-image-"+receipt.applicationId,image.getString("id"));
    }

    @Test public void receiptFieldMutationsAreRejectedByPureParser() throws Exception {
        JSONObject root=fixture(), valid=root.getJSONObject("validSchema11");
        JSONObject validReceipt=valid.getJSONArray("images").getJSONObject(0).getJSONObject("agentImage");
        JSONArray cases=root.getJSONArray("cases");
        for(int i=0;i<cases.length();i++){
            JSONObject vector=cases.getJSONObject(i);if(vector.getBoolean("accepted"))continue;
            JSONObject mutation=vector.optJSONObject("mutation");if(mutation==null)continue;
            if(mutation.has("schemaVersion")||mutation.has("attachReceipt")||mutation.has("duplicateImage")
                    ||mutation.has("image.id")||mutation.has("receipt.pngSha256"))continue;
            JSONObject receipt=new JSONObject(validReceipt.toString());String id=vector.getString("id");
            if(mutation.has("receipt.schemaVersion"))receipt.put("schemaVersion",mutation.get("receipt.schemaVersion"));
            if(mutation.has("receipt.removeField"))receipt.remove(mutation.getString("receipt.removeField"));
            if(mutation.has("receipt.futureField"))receipt.put("futureField",true);
            if(mutation.has("receipt.artifactName"))receipt.put("artifactName",mutation.getString("receipt.artifactName"));
            if(mutation.has("receipt.sourceNoteRevision"))receipt.put("sourceNoteRevision",mutation.getInt("receipt.sourceNoteRevision"));
            if(mutation.has("receipt.addedAt"))receipt.put("addedAt",mutation.get("receipt.addedAt"));
            if(mutation.has("receipt.sizeBytes"))receipt.put("sizeBytes",mutation.get("receipt.sizeBytes"));
            if(mutation.has("receipt.mediaType"))receipt.put("mediaType",mutation.getString("receipt.mediaType"));
            if(mutation.has("receipt.applicationId"))receipt.put("applicationId",mutation.getString("receipt.applicationId"));
            if(!mutation.has("receipt.schemaVersion")&&!mutation.has("receipt.removeField")
                    &&!mutation.has("receipt.futureField")&&!mutation.has("receipt.artifactName")
                    &&!mutation.has("receipt.sourceNoteRevision")&&!mutation.has("receipt.addedAt")
                    &&!mutation.has("receipt.sizeBytes")&&!mutation.has("receipt.mediaType")
                    &&!mutation.has("receipt.applicationId"))continue;
            assertThrows("expected rejection: "+id,Exception.class,()->AgentImageReceipt.fromJson(receipt));
        }
    }

    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
