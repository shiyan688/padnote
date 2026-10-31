package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs document/image validation with Android's real JSON, Base64 and BitmapFactory implementations. */
@RunWith(AndroidJUnit4.class)
public final class AgentImageReceiptCorpusInstrumentedTest {
    private JSONObject fixture()throws Exception{
        Context instrumentation=InstrumentationRegistry.getInstrumentation().getContext();
        try(InputStream in=instrumentation.getAssets().open("AGENT_IMAGE_RECEIPT_CASES_R1.json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))>=0)if(n>0)out.write(buffer,0,n);
            return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
    }

    @Test public void allSharedReceiptVectorsValidateOnAndroid()throws Exception{
        JSONObject root=fixture(),valid=root.getJSONObject("validSchema11");
        NoteStore.validateDocument(valid);
        NoteStore.validateDocument(root.getJSONObject("validSchema10"));
        NoteStore.validateDocument(root.getJSONObject("validSchema9"));
        assertNotNull(TypedNoteTimeProjection.nativeAndroid(fullDocument(valid).toString()));
        JSONArray cases=root.getJSONArray("cases");int rejected=0;
        for(int i=0;i<cases.length();i++){
            JSONObject vector=cases.getJSONObject(i);if(vector.getBoolean("accepted"))continue;rejected++;
            if(vector.has("rawDocumentJSON")){
                assertThrows("Android must reject raw vector: "+vector.getString("id"),Exception.class,
                        ()->NoteStore.validateDocument(new JSONObject(vector.getString("rawDocumentJSON"))));
                continue;
            }
            JSONObject mutation=vector.optJSONObject("mutation");assertNotNull("missing mutation: "+vector.getString("id"),mutation);
            JSONObject document=new JSONObject(valid.toString());
            JSONObject image=document.getJSONArray("images").getJSONObject(0);
            JSONObject receipt=image.getJSONObject("agentImage");
            if(mutation.has("schemaVersion"))document.put("schemaVersion",mutation.get("schemaVersion"));
            if(mutation.has("attachReceipt"))document.put("schemaVersion",mutation.get("schemaVersion"));
            if(mutation.has("receipt.schemaVersion"))receipt.put("schemaVersion",mutation.get("receipt.schemaVersion"));
            if(mutation.has("receipt.removeField"))receipt.remove(mutation.getString("receipt.removeField"));
            if(mutation.has("receipt.futureField"))receipt.put("futureField",true);
            if(mutation.has("receipt.pngSha256"))receipt.put("pngSha256",mutation.getString("receipt.pngSha256"));
            if(mutation.has("image.id"))image.put("id",mutation.getString("image.id"));
            if(mutation.has("receipt.artifactName"))receipt.put("artifactName",mutation.getString("receipt.artifactName"));
            if(mutation.has("receipt.sourceNoteRevision"))receipt.put("sourceNoteRevision",mutation.get("receipt.sourceNoteRevision"));
            if(mutation.has("receipt.addedAt"))receipt.put("addedAt",mutation.get("receipt.addedAt"));
            if(mutation.has("receipt.sizeBytes"))receipt.put("sizeBytes",mutation.get("receipt.sizeBytes"));
            if(mutation.has("receipt.mediaType"))receipt.put("mediaType",mutation.getString("receipt.mediaType"));
            if(mutation.has("receipt.applicationId"))receipt.put("applicationId",mutation.getString("receipt.applicationId"));
            if(mutation.optBoolean("duplicateImage",false))document.getJSONArray("images").put(new JSONObject(image.toString()));
            assertThrows("Android must reject vector: "+vector.getString("id"),Exception.class,
                    ()->NoteStore.validateDocument(document));
        }
        assertEquals("all 18 rejecting vectors are exercised",18,rejected);
    }

    private static JSONObject fullDocument(JSONObject source)throws Exception{
        JSONObject doc=new JSONObject(source.toString()).put("title","Synthetic receipt fixture")
                .put("pageWidth",612d).put("pageHeight",792d).put("canvasWidth",612d)
                .put("canvasHeight",792d).put("pageGap",18d).put("pageCount",1).put("pdfPageCount",0)
                .put("pageTopologyRevision",0).put("authorPageEditSerial",0).put("authorPageTopologySerial",0)
                .put("viewportScale",1d).put("viewportZoom",1d).put("viewportCenterX",306d).put("viewportCenterY",396d)
                .put("pageStyle",new JSONObject().put("paper","ruled").put("ratio","screen").put("landscape",false))
                .put("textBoxes",new JSONArray());
        JSONArray flows=doc.getJSONArray("textFlows");
        for(int i=0;i<flows.length();i++){
            JSONObject flow=flows.getJSONObject(i);flow.put("fontSizeSp",16d).put("lineHeight",1.45d)
                    .put("width",400d).put("anchorPageIndex",0).put("anchorXInPage",16d).put("anchorYInPage",16d);
        }
        return doc;
    }
}
