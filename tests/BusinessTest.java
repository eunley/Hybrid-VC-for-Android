import com.svc.voice.*;import java.io.*;import java.nio.file.*;import org.json.*;
public class BusinessTest {
 static int checks;static LocalHttpApi api;static File base;
 static LocalHttpWire.Reply call(String m,String p,String... f){LocalHttpWire.Request r=new LocalHttpWire.Request();r.method=m;r.path="/api/v1"+p;for(int i=0;i<f.length;i+=2)r.fields.put(f[i],f[i+1]);return api.handle(r);}
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static void code(int n,LocalHttpWire.Reply r){check(r.status==n,"expected "+n+" actual "+r.status+" "+r.json);}
 static String task(LocalHttpWire.Reply r)throws Exception{code(202,r);return new JSONObject(r.json).getString("id");}
 static LocalHttpWire.Reply upload(String path,File f,String... fields){LocalHttpWire.Request r=new LocalHttpWire.Request();r.method="POST";r.path="/api/v1"+path;r.files.put("file",f);for(int i=0;i<fields.length;i+=2)r.fields.put(fields[i],fields[i+1]);return api.handle(r);}
 public static void main(String[] args)throws Exception{
 android.content.Context c=new android.content.Context();Prefs p=new Prefs(c);base=p.baseDir();api=new LocalHttpApi(c);File role=new File(p.rolesDir(),"Test");role.mkdirs();Files.write(new File(role,"dec.onnx").toPath(),new byte[1025]);Files.write(new File(role,"enc.onnx").toPath(),new byte[2]);
 code(200,call("GET","/roles"));check(call("GET","/roles").json.contains("Test"),"role list");
 code(404,call("GET","/absent"));code(400,call("DELETE","/roles/.."));code(400,call("DELETE","/roles/"+"x".repeat(101)));code(404,call("DELETE","/roles/Missing"));
 code(400,call("PUT","/config","threads","-1"));code(400,call("PUT","/config","threads","9"));code(400,call("PUT","/config","npu","yes"));code(200,call("PUT","/config","npu","true","threads","3"));check(p.device().equals("npu")&&p.cpuThreads()==3,"prefs config");
 Busy.acquire("rt");code(409,call("DELETE","/roles/Test"));code(409,call("PUT","/config","threads","2"));code(409,call("POST","/tasks"));Busy.release("rt");
 File input=File.createTempFile("audio", ".wav");Files.write(input.toPath(),new byte[100]);
 code(400,upload("/roles/import",input,"name","Bad"));check(!new File(p.rolesDir(),"Bad").exists(),"failed import not published");
 File zip=File.createTempFile("evil", ".zip");try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(new FileOutputStream(zip))){z.putNextEntry(new java.util.zip.ZipEntry("../escape"));z.write(1);z.closeEntry();}
 code(400,upload("/roles/import",zip,"name","Bad"));
 code(400,upload("/tasks",input,"role","Test","frames","0"));code(400,upload("/tasks",input,"role","Test","bufferFrames","129"));code(400,upload("/tasks",input,"role","Test","pitchSemitones","25"));code(400,upload("/tasks",input,"role","Test","denoise","1"));code(400,upload("/tasks",input,"role","Test","volumeLimit","1"));code(400,upload("/tasks",input,"role","Test","volumeLimit","TRUE"));code(400,call("POST","/tasks","role","Test"));
 String id=task(upload("/tasks",input,"role","Test","frames","128","bufferFrames","8","pitchSemitones","-3","denoise","true","volumeLimit","true"));
 check(call("GET","/tasks/"+id).json.contains("\"volumeLimit\":true"),"volume limit enabled status");
 code(409,call("POST","/tasks","role","Test"));code(409,call("DELETE","/roles/Test"));code(409,call("GET","/tasks/"+id+"/result"));code(200,call("POST","/tasks/"+id+"/cancel"));
 while(api.busy())Thread.sleep(10);check(call("GET","/tasks/"+id).json.contains("cancelled"),"cancelled status");check(Busy.holder()==null&&!JobService.cancelReq,"released cancel");
 Busy.acquire("rt");code(200,call("POST","/tasks/"+id+"/cancel"));check(!JobService.cancelReq,"terminal cancel does not cancel realtime");Busy.release("rt");
 id=task(upload("/tasks",input,"role","Test"));while(api.busy())Thread.sleep(10);check(call("GET","/tasks/"+id).json.contains("succeeded"),"success status");check(call("GET","/tasks/"+id).json.contains("\"volumeLimit\":false"),"volume limit defaults false");code(200,call("GET","/tasks/"+id+"/result"));
 String compile=task(call("POST","/roles/Test/compilations","frames","128"));code(409,call("POST","/tasks/"+compile+"/cancel"));while(api.busy())Thread.sleep(10);code(200,call("DELETE","/roles/Test/compilations","frames","128"));
 code(200,call("GET","/logs"));code(200,call("DELETE","/logs"));code(200,call("DELETE","/roles/Test"));code(404,call("DELETE","/roles/Test"));api.close();input.delete();zip.delete();System.out.println("PASS "+checks+" business assertions (host Android/engine stubs; no native inference)");
 }
}
