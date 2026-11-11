package com.svc.voice;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class WireTest {
 static int port,checks;static File temp;static byte[] expected;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 static String exchange(String headers,byte[] body,int split)throws Exception{
  try(Socket s=new Socket("127.0.0.1",port)){s.setSoTimeout(20000);OutputStream o=s.getOutputStream();o.write(headers.getBytes(StandardCharsets.UTF_8));
   if(body!=null)for(int i=0;i<body.length;i+=split){o.write(body,i,Math.min(split,body.length-i));o.flush();}
   s.shutdownOutput();return new String(s.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
  }
 }
 static void status(String h,int n)throws Exception{String r=exchange(h,null,1);check(r.startsWith("HTTP/1.1 "+n+" "),r);check(r.contains("\"error\":{\"code\":"+n),r);}
 static String head(String path,int n,String more){return "POST "+path+" HTTP/1.1\r\nHost: localhost\r\nContent-Length: "+n+"\r\n"+more+"\r\n";}
 public static void main(String[] args)throws Exception{
  temp=Files.createTempDirectory("wire-test-").toFile();
  LocalHttpWire w=new LocalHttpWire("127.0.0.1",0,temp,r->{
   if(r.path.equals("/missing"))throw new LocalHttpWire.Error(404,"gone");
   if(r.path.equals("/conflict"))throw new LocalHttpWire.Error(409,"a\"b\nc");
   if(r.path.equals("/fail"))throw new Exception("secret");
   if(r.path.equals("/multipart")){check("你好".equals(r.fields.get("title")),"field");check(Arrays.equals(expected,Files.readAllBytes(r.files.get("upload").toPath())),"file bytes");return LocalHttpWire.Reply.file(r.files.get("upload"),"application/octet-stream");}
   if(r.path.equals("/large")){check(r.body.length()==128L*1024*1024,"max length");return new LocalHttpWire.Reply(200,"{}");}
   check(r.path.equals("/a+b/你"),"decoded path "+r.path);check(r.body.length()==3,"raw body");return new LocalHttpWire.Reply(200,"{}");
  });port=w.server.getLocalPort();
  try{
   check(exchange(head("/a+b/%E4%BD%A0?q=x",3,""),new byte[]{1,2,3},1).startsWith("HTTP/1.1 200"),"raw");
   for(String p:new String[]{"/missing","/conflict","/fail"})status(head(p,0,""),p.equals("/missing")?404:p.equals("/conflict")?409:500);
   status(head("/",0,"Transfer-Encoding: chunked\r\n"),400);
   status(head("/",0,"Content-Length: 0\r\n"),400);
   status(head("/",134217729,""),400);
   status("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n",400);
   status(head("/%zz",0,""),400);
   status(head("/%C0%AF",0,""),400);
   status(head("/",0,"X: "+"a".repeat(32768)+"\r\n"),400);
   status(head("/",10,""),400);
   String boundary="AaB03x";
   for(int split:new int[]{1,2,7,8191,8192,8193}){
    expected=("z".repeat(8179)+"\r\n--AaB03xXY\r\n--AaB03\r\n--AaB03xQZ"+"a".repeat(9000)).getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n你好\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"../../evil\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));b.write(expected);b.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
    byte[] data=b.toByteArray();String reply=exchange(head("/multipart",data.length,"Content-Type: multipart/form-data; boundary=\""+boundary+"\"\r\n"),data,split);check(reply.startsWith("HTTP/1.1 200"),reply);check(reply.endsWith(new String(expected,StandardCharsets.UTF_8)),"file response");
   }
   byte[] broken="--x\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a\"\r\n\r\nabc".getBytes();
   check(exchange(head("/",broken.length,"Content-Type: multipart/form-data; boundary=x\r\n"),broken,1).startsWith("HTTP/1.1 400"),"truncated multipart");
   try(Socket s=new Socket("127.0.0.1",port)){s.setSoTimeout(25000);s.getOutputStream().write(head("/",1,"").getBytes());long t=System.nanoTime();String response=new String(s.getInputStream().readAllBytes());check(response.startsWith("HTTP/1.1 400"),"idle timeout");check((System.nanoTime()-t)/1e9>=14,"15s timeout");}
   try(Socket s=new Socket("127.0.0.1",port)){s.setSoTimeout(20000);s.getOutputStream().write(head("/large",134217728,"").getBytes());byte[] block=new byte[65536];for(int i=0;i<2048;i++)s.getOutputStream().write(block);check(new String(s.getInputStream().readAllBytes()).startsWith("HTTP/1.1 200"),"128MiB accepted under small heap");}
   List<Socket> waiting=new ArrayList<>();for(int i=0;i<25;i++)waiting.add(new Socket("127.0.0.1",port));Thread.sleep(300);check(w.workers.getPoolSize()<=4&&w.workers.getQueue().size()<=16,"bounded executor");w.close();for(Socket s:waiting){s.setSoTimeout(2000);try{check(s.getInputStream().read()==-1,"closed connection");}catch(SocketException e){checks++;}s.close();}
   check(!w.acceptor.isAlive()&&w.workers.isTerminated()&&w.timer.isShutdown(),"shutdown");
   for(int i=0;i<100&&temp.list().length!=0;i++)Thread.sleep(10);check(temp.list().length==0,"temp cleanup");
   System.out.println("PASS "+checks+" checks");
  }finally{w.close();for(File f:temp.listFiles())f.delete();temp.delete();}
 }
}
