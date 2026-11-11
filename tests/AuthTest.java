package com.svc.voice;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Real sockets; the route handler is deliberately independent of Android. */
public class AuthTest {
 static int checks;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
 static String send(LocalHttpWire w,String method,String path,String auth,long size,byte[] body)throws Exception{
  try(Socket s=new Socket("127.0.0.1",w.server.getLocalPort())){
   s.setSoTimeout(2000);
   s.getOutputStream().write((method+" "+path+" HTTP/1.1\r\nHost: localhost\r\nContent-Length: "+size+"\r\n"+auth+"\r\n").getBytes(StandardCharsets.US_ASCII));
   if(body!=null)s.getOutputStream().write(body);
   s.getOutputStream().flush(); // No shutdownOutput: unauthorized requests must reply without waiting for a body.
   return new String(s.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
  }
 }
 static void status(String r,int n){check(r.startsWith("HTTP/1.1 "+n+" "),r);}
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("auth-test-");File temp=dir.toFile();AtomicInteger calls=new AtomicInteger();
  LocalHttpWire.Handler handler=r->{calls.incrementAndGet();if(r.path.endsWith("/result")){check(r.body.length()==0,"download body");return LocalHttpWire.Reply.file(r.body,"audio/wav");}return new LocalHttpWire.Reply(200,"{}");};
  try{
   check(LocalHttpWire.validateToken(""),"empty");check(LocalHttpWire.validateToken("!~"),"visible");check(LocalHttpWire.validateToken("a".repeat(256)),"256");
   for(String bad:new String[]{null," ","a b","a\t","a\n","a\r","\u0000","\u007f","é","密钥","a".repeat(257)}){
    check(!LocalHttpWire.validateToken(bad),"invalid token");
    try(LocalHttpWire ignored=new LocalHttpWire("127.0.0.1",0,temp,handler,bad)){throw new AssertionError("accepted invalid token");}catch(IllegalArgumentException expected){check(!expected.getMessage().contains("密钥"),"generic validation");}
   }
   try(LocalHttpWire w=new LocalHttpWire("127.0.0.1",0,temp,handler)){
    status(send(w,"GET","/api/v1/roles","",0,null),200);
    status(send(w,"GET","/api/v1/roles","Authorization: garbage\r\n",0,null),200);
   }
   try(LocalHttpWire w=new LocalHttpWire("127.0.0.1",0,temp,handler,"")){
    status(send(w,"GET","/","Authorization: Bearer wrong\r\n",0,null),200);
   }
   String token="Sensitive-key!0123456789";
   try(LocalHttpWire w=new LocalHttpWire("127.0.0.1",0,temp,handler,token)){
    String generic=null;
    for(String auth:new String[]{"","Authorization: Bearer wrong\r\n","Authorization: Basic "+token+"\r\n","Authorization: Bearer\r\n","Authorization: Bearer sensitive-key!0123456789\r\n"}){
     int before=calls.get();String r=send(w,"POST","/api/v1/uploads",auth,Long.MAX_VALUE,null);status(r,401);
     check(calls.get()==before,"unauthorized handler");check(!r.contains(token)&&!r.contains("wrong"),"secret leak");
     if(generic==null)generic=r;else check(generic.equals(r),"uniform unauthorized response");
     check(temp.list().length==0,"no body temp files");
    }
    for(String scheme:new String[]{"Bearer","bearer","BEARER","bEaReR"})status(send(w,"GET","/api/v1/roles","aUtHoRiZaTiOn: "+scheme+" "+token+"\r\n",0,null),200);
    int before=calls.get();status(send(w,"POST","/api/v1/uploads","Authorization: Bearer "+token+"\r\nauthorization: Bearer "+token+"\r\n",999999999,null),400);check(calls.get()==before,"duplicate handler");
    String[] routes={"GET /api/v1/roles","POST /api/v1/roles/import","DELETE /api/v1/roles/alice","POST /api/v1/roles/alice/compilations","DELETE /api/v1/roles/alice/compilations","GET /api/v1/logs","DELETE /api/v1/logs","PUT /api/v1/config","POST /api/v1/tasks","GET /api/v1/tasks/id","GET /api/v1/tasks/id/result","POST /api/v1/tasks/id/cancel","POST /api/v1/uploads","GET /api/v1/uploads/id","PUT /api/v1/uploads/id/chunks/0","POST /api/v1/uploads/id/complete","DELETE /api/v1/uploads/id","GET /unknown"};
    for(String route:routes){String[] p=route.split(" ");before=calls.get();status(send(w,p[0],p[1],"",134217728,null),401);check(calls.get()==before,"route blocked "+route);
     status(send(w,p[0],p[1],"Authorization: Bearer wrong\r\n",134217728,null),401);check(calls.get()==before,"wrong route blocked "+route);
     status(send(w,p[0],p[1],"Authorization: Bearer "+token+"\r\n",0,null),200);check(calls.get()==before+1,"route allowed "+route);
    }
    byte[] data="raw chunk bytes".getBytes(StandardCharsets.US_ASCII);status(send(w,"PUT","/api/v1/uploads/id/chunks/0","Authorization: Bearer "+token+"\r\n",data.length,data),200);
   }
   System.out.println("PASS AuthTest "+checks+" checks (all route shapes, real sockets)");
  }finally{for(File f:temp.listFiles())f.delete();Files.delete(dir);}
 }
}
