package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

/** Source-authoritative timestamp projection for schema-8 update prototypes.
 * Provenance is input-only here; this is not a durable storage adapter. */
final class TypedNoteTimeProjection {
    enum Kind { I64, F64 }
    static final class Time { final String pointer; final Kind kind; final long integer,doubleBits;
        Time(String p,Kind k,long i,long b){pointer=p;kind=k;integer=i;doubleBits=b;} }
    static final class ProvenanceValue { final Kind kind; final long integer,doubleBits;
        ProvenanceValue(Kind k,long i,long b){kind=k;integer=i;doubleBits=b;} }
    static final class Sidecar { final String source,sha256,noteId; final Map<String,ProvenanceValue> values;
        Sidecar(String s,String sha,String id,Map<String,ProvenanceValue> v){source=s;sha256=sha;noteId=id;values=v;} }
    static final class Projection { final String source,noteId,rawSha256; final List<Time> times; final List<String> negativeZeroPaths;
        /** Shelf retirement is system metadata, never part of the ordinary canvas projection. */
        final String shelfState; final long shelfRetiredAt;
        final byte[] bodyBytes;
        Projection(String s,String id,String sha,List<Time> t,List<String> z,String shelf,long retiredAt,byte[] body){source=s;noteId=id;rawSha256=sha;times=immutableCopy(t);negativeZeroPaths=immutableCopy(z);shelfState=shelf;shelfRetiredAt=retiredAt;bodyBytes=body.clone();} }
    private TypedNoteTimeProjection(){}
    static Projection nativeAndroid(String raw)throws Exception {
        rejectDuplicateKeys(raw);
        JSONObject doc=NotePrecisionJsonParser.parseObject(raw); if(doc.optInt("schemaVersion",0)!=8)throw new IllegalArgumentException("update_requires_schema8");
        requireExactTimestampFields(doc);
        NoteStore.validateDocument(doc); return project(doc,"native-android",null,raw);
    }
    static Projection transported(String raw,Sidecar sidecar)throws Exception {
        rejectDuplicateKeys(raw);
        if(sidecar==null||!("native-ios".equals(sidecar.source)||"transport".equals(sidecar.source)||"native-android".equals(sidecar.source)))throw new IllegalArgumentException("typed_provenance_required");
        if(!sha(raw.getBytes(StandardCharsets.UTF_8)).equals(sidecar.sha256))throw new IllegalArgumentException("provenance_document_hash_mismatch");
        Set<String> f64NegativeZeroTimes=new HashSet<>();
        for(Map.Entry<String,ProvenanceValue> entry:sidecar.values.entrySet())if(entry.getValue().kind==Kind.F64&&entry.getValue().doubleBits==Long.MIN_VALUE)f64NegativeZeroTimes.add(entry.getKey());
        JSONObject doc=NotePrecisionJsonParser.parseObject(raw,f64NegativeZeroTimes); if(doc.optInt("schemaVersion",0)!=8)throw new IllegalArgumentException("update_requires_schema8");
        requireExactTimestampFields(doc);
        NoteStore.validateDocument(doc); if(!doc.getString("id").equals(sidecar.noteId))throw new IllegalArgumentException("provenance_note_id_mismatch");
        Projection p=project(doc,sidecar.source,sidecar.values,raw);
        if(sidecar.values.size()!=p.times.size())throw new IllegalArgumentException("provenance_path_set_mismatch"); return p;
    }
    private static Projection project(JSONObject doc,String source,Map<String,ProvenanceValue> explicit,String raw)throws Exception {
        ArrayList<Time> times=new ArrayList<>(); add(doc,"/updatedAt",source,explicit,times);
        JSONArray strokes=doc.getJSONArray("strokes"); for(int si=0;si<strokes.length();si++){
            JSONObject stroke=strokes.getJSONObject(si); add(stroke,"/strokes/"+si+"/createdAt",source,explicit,times);
            JSONArray points=stroke.getJSONArray("points"); for(int pi=0;pi<points.length();pi++)add(points.getJSONObject(pi),"/strokes/"+si+"/points/"+pi+"/timestamp",source,explicit,times);
        }
        ArrayList<String> zeros=new ArrayList<>(); collectZeros(doc,"",zeros); Collections.sort(zeros);
        String shelfState=null; long shelfRetiredAt=0;
        boolean hasShelfState=doc.has("_padnoteShelfState"),hasShelfRetiredAt=doc.has("_padnoteShelfRetiredAt");
        if(hasShelfState!=hasShelfRetiredAt)throw new IllegalArgumentException("shelf_retirement_metadata_pair_required");
        if(hasShelfState){
            Object state=doc.get("_padnoteShelfState"),retiredAt=doc.get("_padnoteShelfRetiredAt");
            if(!(state instanceof String)||!"retired".equals(state))throw new IllegalArgumentException("shelf_retirement_state_invalid");
            if(!(retiredAt instanceof Byte||retiredAt instanceof Short||retiredAt instanceof Integer||retiredAt instanceof Long)
                    ||((Number)retiredAt).longValue()<=0)throw new IllegalArgumentException("shelf_retirement_time_invalid");
            shelfState=(String)state;shelfRetiredAt=((Number)retiredAt).longValue();
        }
        // The exact UTF-8 JSON and its digest retain these system fields. Only the strict
        // author-canvas projection omits them; arbitrary unknown canvas fields still fail.
        JSONObject canvasDoc=new JSONObject();
        for(Iterator<String> keys=doc.keys();keys.hasNext();){
            String key=keys.next();
            if(!key.equals("_padnoteShelfState")&&!key.equals("_padnoteShelfRetiredAt"))canvasDoc.put(key,doc.get(key));
        }
        return new Projection(source,doc.getString("id"),sha(raw.getBytes(StandardCharsets.UTF_8)),times,zeros,
                shelfState,shelfRetiredAt,encodeBody(canvasDoc,times));
    }
    private static byte[] encodeBody(JSONObject doc,List<Time> times)throws Exception {
        requireKeys(doc,keySet("schemaVersion","id","title","updatedAt","pageWidth","pageHeight","canvasWidth","canvasHeight","pageGap","pageCount","pdfPageCount","pageTopologyRevision","authorPageEditSerial","authorPageTopologySerial","viewportScale","viewportZoom","viewportCenterX","viewportCenterY","strokes","textFlows","images","pageStyle","textBoxes"));
        // Canvas-owned counters and viewport state are persisted in the exact JSON
        // body and restored by NoteCanvasView. They are editor metadata, so they do not
        // enter the author-content projection. The canvas clamps finite viewportScale
        // values to its own supported range when loading; do not narrow source values here.
        validatePageRevision(doc,"authorPageEditSerial");
        validatePageRevision(doc,"authorPageTopologySerial");
        validateFiniteViewportScale(doc);
        if(doc.has("textBoxes")&&doc.getJSONArray("textBoxes").length()!=0)throw new IllegalArgumentException("legacy_textboxes_not_empty");
        if(doc.getString("title").length()>80)throw new IllegalArgumentException("title_exceeds_android_storage_bound");
        JSONObject style;
        if(doc.has("pageStyle")) {
            Object value=doc.get("pageStyle");
            if(!(value instanceof JSONObject))throw new IllegalArgumentException("page_style_exact");
            style=(JSONObject)value;
            requireKeys(style,keySet("paper","ratio","landscape"));
            if(!oneOf(style.getString("paper"),"blank","ruled","grid","dotted")||!oneOf(style.getString("ratio"),"screen","a4"))throw new IllegalArgumentException("page_style_exact");
            if(!(style.get("landscape") instanceof Boolean))throw new IllegalArgumentException("page_style_exact");
        } else {
            // NoteCanvasView.loadJsonDocument uses PageStyle.fromJson(null),
            // which is PageStyle.legacyDefault(): ruled, screen, portrait.
            style=new JSONObject().put("paper","ruled").put("ratio","screen").put("landscape",false);
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.write("PadNote/ActualNote/kind-v2\0".getBytes(StandardCharsets.UTF_8));putBytes(out,doc.getString("title").getBytes(StandardCharsets.UTF_8));
        // NoteStore.emptyDocument writes zero dimensions before the canvas has
        // been measured. The canvas reader keeps those as deferred geometry;
        // encoding zero preserves that state instead of inventing page sizes.
        double pageWidth=storedGeometry(doc,"pageWidth","canvasWidth",0d);
        double pageHeight=storedGeometry(doc,"pageHeight","canvasHeight",0d);
        double pageGap=storedGeometry(doc,"pageGap",null,0d);
        if(pageGap>10000)throw new IllegalArgumentException("page_geometry_outside_exact_storage_range");
        for(double d:new double[]{pageWidth,pageHeight,pageGap})putF64(out,d);
        putI64(out,strictInteger(doc,"pageCount"));putI64(out,NoteStore.pdfPageCountOrZero(doc));
        putBytes(out,style.getString("paper").getBytes(StandardCharsets.UTF_8));putBytes(out,style.getString("ratio").getBytes(StandardCharsets.UTF_8));out.writeByte(style.getBoolean("landscape")?1:0);
        JSONArray strokes=doc.getJSONArray("strokes");putCount(out,strokes.length());Map<String,Time> map=new HashMap<>();for(Time t:times)map.put(t.pointer,t);
        for(int si=0;si<strokes.length();si++){
            JSONObject s=strokes.getJSONObject(si);requireKeys(s,keySet("id","color","baseWidth","createdAt","highlighter","points"));
            putBytes(out,s.getString("id").getBytes(StandardCharsets.UTF_8));String color=s.getString("color");if(!color.matches("#[0-9A-Fa-f]{8}"))throw new IllegalArgumentException("stroke_color");byte[] rgba=new byte[4];for(int i=0;i<4;i++)rgba[i]=(byte)Integer.parseInt(color.substring(1+i*2,3+i*2),16);putBytes(out,rgba);
            putF64(out,s.getDouble("baseWidth"));putTime(out,requiredTime(map,"/strokes/"+si+"/createdAt"));out.writeByte(s.optBoolean("highlighter",false)?1:0);
            JSONArray pts=s.getJSONArray("points");putCount(out,pts.length());for(int pi=0;pi<pts.length();pi++){JSONObject p=pts.getJSONObject(pi);requireKeys(p,keySet("x","y","pressure","timestamp"));putF64(out,p.getDouble("x"));putF64(out,p.getDouble("y"));putF64(out,p.has("pressure")?p.getDouble("pressure"):0.5);putTime(out,requiredTime(map,"/strokes/"+si+"/points/"+pi+"/timestamp"));}
        }
        JSONArray flows=doc.optJSONArray("textFlows");if(flows==null)flows=new JSONArray();putCount(out,flows.length());for(int i=0;i<flows.length();i++){JSONObject f=flows.getJSONObject(i);requireKeys(f,keySet("id","format","source","fontSizeSp","lineHeight","width","anchorPageIndex","anchorXInPage","anchorYInPage"));double font=f.getDouble("fontSizeSp"),line=f.getDouble("lineHeight"),width=f.getDouble("width");if(font<10||font>32||line<1.1||line>2||width<80)throw new IllegalArgumentException("text_flow_outside_android_exact_storage_range");for(String k:new String[]{"id","format","source"})putBytes(out,f.getString(k).getBytes(StandardCharsets.UTF_8));for(double d:new double[]{font,line,width})putF64(out,d);putI64(out,strictInteger(f,"anchorPageIndex"));putF64(out,f.getDouble("anchorXInPage"));putF64(out,f.getDouble("anchorYInPage"));}
        JSONArray images=doc.optJSONArray("images");if(images==null)images=new JSONArray();putCount(out,images.length());for(int i=0;i<images.length();i++){JSONObject im=images.getJSONObject(i);requireKeys(im,keySet("id","png","page","x","y","width","height"));putBytes(out,im.getString("id").getBytes(StandardCharsets.UTF_8));String encoded=im.getString("png");byte[] png=decodeCanonicalBase64(encoded);if(!encodeCanonicalBase64(png).equals(encoded))throw new IllegalArgumentException("image_base64");putBytes(out,png);putI64(out,strictInteger(im,"page"));for(String k:new String[]{"x","y","width","height"})putF64(out,im.getDouble(k));}
        out.flush();return bytes.toByteArray();
    }
    private static Time requiredTime(Map<String,Time> values,String path){Time t=values.get(path);if(t==null)throw new IllegalArgumentException("time_path_missing:"+path);return t;}
    private static void putTime(DataOutputStream out,Time t)throws Exception{out.writeByte(t.kind==Kind.I64?2:3);putI64(out,t.kind==Kind.I64?t.integer:t.doubleBits);}
    private static void putF64(DataOutputStream out,double d)throws Exception{if(!Double.isFinite(d))throw new IllegalArgumentException("nonfinite");putI64(out,Double.doubleToRawLongBits(d));}
    private static void putI64(DataOutputStream out,long value)throws Exception{out.writeLong(value);}
    private static void putCount(DataOutputStream out,int n)throws Exception{if(n<0)throw new IllegalArgumentException("count");out.writeInt(n);}
    private static void putBytes(DataOutputStream out,byte[] value)throws Exception{putCount(out,value.length);out.write(value);}
    private static <T> List<T> immutableCopy(Collection<T> values){return Collections.unmodifiableList(new ArrayList<>(values));}
    private static Set<String> keySet(String... values){HashSet<String> result=new HashSet<>();Collections.addAll(result,values);return result;}
    private static boolean oneOf(String value,String... choices){for(String choice:choices)if(choice.equals(value))return true;return false;}

    /** Strict RFC 4648 standard alphabet codec, kept local for minSdk 24. */
    static byte[] decodeCanonicalBase64(String value){
        if(value==null||value.length()%4!=0)throw new IllegalArgumentException("image_base64");
        int length=value.length(),padding=0;
        if(length>0&&value.charAt(length-1)=='=')padding++;
        if(length>1&&value.charAt(length-2)=='=')padding++;
        int outputLength=(length/4)*3-padding;byte[] output=new byte[outputLength];int out=0;
        for(int i=0;i<length;i+=4){
            int a=base64Value(value.charAt(i)),b=base64Value(value.charAt(i+1));
            int c=value.charAt(i+2)=='='?-1:base64Value(value.charAt(i+2));
            int d=value.charAt(i+3)=='='?-1:base64Value(value.charAt(i+3));
            boolean last=i+4==length;
            if(a<0||b<0||(!last&&(c<0||d<0))||(!last&&(value.charAt(i+2)=='='||value.charAt(i+3)=='='))
                    ||(c<0&&value.charAt(i+3)!='=')||(value.charAt(i+2)=='='&&value.charAt(i+3)!='=')
                    ||(c<0&&(b&15)!=0)||(d<0&&c>=0&&(c&3)!=0))throw new IllegalArgumentException("image_base64");
            int packed=(a<<18)|(b<<12)|((c<0?0:c)<<6)|(d<0?0:d);
            if(out<outputLength)output[out++]=(byte)(packed>>>16);
            if(out<outputLength)output[out++]=(byte)(packed>>>8);
            if(out<outputLength)output[out++]=(byte)packed;
        }
        if(out!=outputLength||!encodeCanonicalBase64(output).equals(value))throw new IllegalArgumentException("image_base64");
        return output;
    }
    static String encodeCanonicalBase64(byte[] value){
        final char[] alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder out=new StringBuilder(((value.length+2)/3)*4);
        for(int i=0;i<value.length;i+=3){
            int a=value[i]&255,b=i+1<value.length?value[i+1]&255:0,c=i+2<value.length?value[i+2]&255:0;
            out.append(alphabet[a>>>2]).append(alphabet[((a&3)<<4)|(b>>>4)]);
            out.append(i+1<value.length?alphabet[((b&15)<<2)|(c>>>6)]:'=');
            out.append(i+2<value.length?alphabet[c&63]:'=');
        }
        return out.toString();
    }
    private static int base64Value(char value){
        if(value>='A'&&value<='Z')return value-'A';if(value>='a'&&value<='z')return value-'a'+26;
        if(value>='0'&&value<='9')return value-'0'+52;if(value=='+')return 62;if(value=='/')return 63;return -1;
    }
    private static long strictInteger(JSONObject object,String key)throws Exception{Object value=object.get(key);if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))throw new IllegalArgumentException("integer_field:"+key);return ((Number)value).longValue();}
    private static double storedGeometry(JSONObject object,String key,String fallback,double absent)throws Exception{
        String actual=object.has(key)?key:(fallback!=null&&object.has(fallback)?fallback:null);
        if(actual==null)return absent;
        Object value=object.get(actual);
        if(!(value instanceof Number))throw new IllegalArgumentException("geometry_not_number:"+actual);
        double result=((Number)value).doubleValue();
        if(!Double.isFinite(result)||result<0d)throw new IllegalArgumentException("page_geometry_outside_exact_storage_range");
        return result;
    }
    private static void requireKeys(JSONObject object,Set<String> allowed)throws JSONException{Iterator<String> it=object.keys();while(it.hasNext())if(!allowed.contains(it.next()))throw new JSONException("unknown_field");}
    private static void validatePageRevision(JSONObject document,String key)throws Exception {
        if(!document.has(key))return;
        long value=strictInteger(document,key);
        if(value<0||value>Long.MAX_VALUE-1024)throw new IllegalArgumentException("page_revision_invalid:"+key);
    }
    private static void validateFiniteViewportScale(JSONObject document)throws Exception {
        if(!document.has("viewportScale"))return;
        Object raw=document.get("viewportScale");
        if(!(raw instanceof Number)||!Double.isFinite(((Number)raw).doubleValue()))
            throw new IllegalArgumentException("viewport_scale_must_be_finite_number");
    }
    private static void requireExactTimestampFields(JSONObject doc)throws Exception{
        requireTimestampNumber(doc,"updatedAt","/updatedAt");JSONArray strokes=doc.getJSONArray("strokes");
        for(int si=0;si<strokes.length();si++){JSONObject stroke=strokes.getJSONObject(si);requireTimestampNumber(stroke,"createdAt","/strokes/"+si+"/createdAt");JSONArray points=stroke.getJSONArray("points");for(int pi=0;pi<points.length();pi++)requireTimestampNumber(points.getJSONObject(pi),"timestamp","/strokes/"+si+"/points/"+pi+"/timestamp");}
    }
    private static void requireTimestampNumber(JSONObject object,String field,String pointer)throws Exception{Object raw=object.get(field);if(!(raw instanceof Number))throw new IllegalArgumentException("timestamp_not_number:"+pointer);}
    private static void add(JSONObject obj,String pointer,String source,Map<String,ProvenanceValue> explicit,List<Time> out)throws Exception {
        Object v=obj.get(pointer.substring(pointer.lastIndexOf('/')+1)); if(!(v instanceof Number))throw new IllegalArgumentException("timestamp_not_number:"+pointer);
        Number n=(Number)v; Kind kind;
        if("native-ios".equals(source))kind=Kind.F64;
        else if(explicit!=null){ProvenanceValue x=explicit.get(pointer);if(x==null)throw new IllegalArgumentException("provenance_path_missing:"+pointer);kind=x.kind;
            if(kind==Kind.I64&&(!isIntegral(n)||!asLongExact(n,x.integer)))throw new IllegalArgumentException("provenance_integer_mismatch:"+pointer);
            if(kind==Kind.F64&&Double.doubleToRawLongBits(n.doubleValue())!=x.doubleBits)throw new IllegalArgumentException("provenance_float_mismatch:"+pointer);
        } else if(isIntegral(n))kind=Kind.I64;
        else if(n instanceof Float||n instanceof Double||n instanceof BigDecimal)kind=Kind.F64; else throw new IllegalArgumentException("unsupported_number_kind:"+pointer);
        if(kind==Kind.I64){long i;try{i=n instanceof BigInteger?bigIntegerToLongExact((BigInteger)n):n.longValue();}catch(ArithmeticException e){throw new IllegalArgumentException("i64_out_of_range:"+pointer);}if(explicit!=null&&explicit.get(pointer).integer!=i)throw new IllegalArgumentException("provenance_integer_mismatch:"+pointer);out.add(new Time(pointer,kind,i,0));}
        else {if("native-ios".equals(source)&&explicit!=null&&explicit.get(pointer).kind!=Kind.F64)throw new IllegalArgumentException("native_ios_times_must_be_f64");double d=n.doubleValue();if(!Double.isFinite(d))throw new IllegalArgumentException("nonfinite_time:"+pointer);long bits=Double.doubleToRawLongBits(d);if(explicit!=null&&explicit.get(pointer).doubleBits!=bits)throw new IllegalArgumentException("provenance_float_mismatch:"+pointer);out.add(new Time(pointer,kind,0,bits));}
    }
    private static boolean isIntegral(Number n){return n instanceof Byte||n instanceof Short||n instanceof Integer||n instanceof Long||n instanceof BigInteger;}
    private static long bigIntegerToLongExact(BigInteger value){long narrowed=value.longValue();if(!BigInteger.valueOf(narrowed).equals(value))throw new ArithmeticException("out of long range");return narrowed;}
    private static boolean asLongExact(Number n,long expected){try{return (n instanceof BigInteger?bigIntegerToLongExact((BigInteger)n):n.longValue())==expected;}catch(ArithmeticException e){return false;}}
    private static void collectZeros(Object v,String p,List<String> out)throws JSONException {
        if(v instanceof JSONObject){JSONObject o=(JSONObject)v;Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();collectZeros(o.get(k),p+"/"+k.replace("~","~0").replace("/","~1"),out);}}
        else if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++)collectZeros(a.get(i),p+"/"+i,out);}
        else if(v instanceof Double&&Double.doubleToRawLongBits((Double)v)==Long.MIN_VALUE)out.add(p);
    }
    private static String sha(byte[] b)throws Exception{byte[] h=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:h)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private static void rejectDuplicateKeys(String json)throws Exception {
        ArrayDeque<Set<String>> objects=new ArrayDeque<>();boolean in=false,escape=false;int start=-1;
        for(int i=0;i<json.length();i++){
            char c=json.charAt(i);
            if(in){if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(c=='"'){in=false;int j=i+1;while(j<json.length()&&Character.isWhitespace(json.charAt(j)))j++;if(j<json.length()&&json.charAt(j)==':'&&!objects.isEmpty()){Object decoded=new JSONTokener(json.substring(start,i+1)).nextValue();if(!(decoded instanceof String)||!objects.peek().add((String)decoded))throw new IllegalArgumentException("duplicate_json_key");}}continue;}
            if(c=='"'){in=true;start=i;}else if(c=='{')objects.push(new HashSet<>());else if(c=='}'&&!objects.isEmpty())objects.pop();
        }
        if(in||!objects.isEmpty())throw new IllegalArgumentException("malformed_json_structure");
    }
}
