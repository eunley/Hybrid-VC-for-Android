import com.svc.voice.LocalHttpWire;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Host-only bridge. Importer is a MOCK: validates ZIP bytes, never loads a model. */
public final class ChunkHttpTest {
 static Object store; static LocalHttpWire wire; static final AtomicInteger imports=new AtomicInteger();
 static File root; static int port;
 static Object open() throws Exception {
  Class<?> type=Class.forName("com.svc.voice.UploadStore");
  for(Constructor<?> c:type.getConstructors()) {
   Class<?>[] p=c.getParameterTypes();
   if(p.length!=2||p[0]!=File.class||!p[1].isInterface())continue;
   Object mock=java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{p[1]},(o,m,a)->{
    if(!m.getName().equals("tryAcquire"))return null;
    if(!com.svc.voice.Busy.acquire("chunk-mock"))return null;
    return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{m.getReturnType()},(lease,l,v)->{
     if(l.getName().equals("close")){com.svc.voice.Busy.release("chunk-mock");return null;}
     if(l.getName().equals("importFile")){
      try(java.util.zip.ZipFile z=new java.util.zip.ZipFile((File)v[0])){byte[] buf=new byte[65536];for(var en=z.entries();en.hasMoreElements();)try(InputStream in=z.getInputStream(en.nextElement())){while(in.read(buf)!=-1){}}}
      imports.incrementAndGet();return "MOCK_IMPORTER: ZIP validated, no model loaded";
     }return null;
    });
   });
   return c.newInstance(root,mock);
  }throw new IllegalStateException("Need public UploadStore(File, interface Importer)");
 }
 static void start() throws Exception {
  store=open(); Method handle=store.getClass().getMethod("handle",LocalHttpWire.Request.class);
  wire=new LocalHttpWire("127.0.0.1",port,new File(root.getParentFile(),"wire"),r->{try{return (LocalHttpWire.Reply)handle.invoke(store,r);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;throw new RuntimeException(t);}});
 }
 static void stop() throws Exception {if(wire!=null)wire.close();if(store instanceof AutoCloseable)((AutoCloseable)store).close();}
 public static void main(String[] args)throws Exception {
  root=new File(args[0]);root.mkdirs();port=Integer.parseInt(args[1]);start();
  System.out.println("READY "+port+" MOCK_IMPORTER");System.out.flush();
  try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in))){String s;while((s=in.readLine())!=null){
   if(s.equals("restart")){stop();start();System.out.println("RESTARTED");}
   else if(s.startsWith("expire ")){stop();File f=new File(new File(root,s.substring(7)),"meta.json");org.json.JSONObject j=new org.json.JSONObject(Files.readString(f.toPath()));j.put("updatedAt",System.currentTimeMillis()-172800000L);Files.writeString(f.toPath(),j.toString());start();System.out.println("EXPIRED");}
   else if(s.equals("imports"))System.out.println("IMPORTS "+imports.get());
   else if(s.equals("busy")){System.out.println("BUSY "+com.svc.voice.Busy.acquire("rt-chunk-test"));}
   else if(s.equals("idle")){com.svc.voice.Busy.release("rt-chunk-test");System.out.println("IDLE");}
   else if(s.equals("quit"))break;
   System.out.flush();
  }}finally{com.svc.voice.Busy.release("rt-chunk-test");stop();}
 }
}
