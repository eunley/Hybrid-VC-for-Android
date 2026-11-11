package com.svc.voice;
import java.io.*;import java.net.*;import java.nio.file.*;
public class WireDeadlineTest {
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("wire-deadline-").toFile();
  try(LocalHttpWire w=new LocalHttpWire("127.0.0.1",0,dir,r->{throw new AssertionError("Incomplete upload reached handler");});Socket s=new Socket("127.0.0.1",w.server.getLocalPort())){
   s.setSoTimeout(75000);s.getOutputStream().write("POST / HTTP/1.1\r\nContent-Length: 1000\r\n\r\n".getBytes());
   Thread sender=new Thread(()->{try{for(int i=0;i<15;i++){s.getOutputStream().write('x');s.getOutputStream().flush();Thread.sleep(5000);}}catch(Exception ignored){}});sender.setDaemon(true);sender.start();
   long start=System.nanoTime();s.getInputStream().readAllBytes();double seconds=(System.nanoTime()-start)/1e9;
   sender.interrupt();sender.join(1000);if(seconds<58||seconds>65)throw new AssertionError("Deadline "+seconds);
   for(int i=0;i<100&&dir.list().length!=0;i++)Thread.sleep(10);if(dir.list().length!=0)throw new AssertionError("Leaked temp");
   System.out.println("PASS 60s total deadline despite 5s trickle; elapsed="+seconds+"; cleanup verified");
  }finally{dir.delete();}
 }
}
