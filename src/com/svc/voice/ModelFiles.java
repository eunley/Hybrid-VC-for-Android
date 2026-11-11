package com.svc.voice;
import java.io.*;
import java.security.*;
/** Bounded-memory verified deployment. Existing user files are never replaced. */
public final class ModelFiles {
 public interface Source { InputStream open(String name) throws IOException; }
 public static synchronized void install(File dir,String name,long size,String hash,Source source) throws Exception {
  if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create model directory: "+dir);
  File dest=new File(dir,name),tmp=new File(dir,name+".bundled.tmp");
  if(dest.exists()){if(!dest.isFile()||dest.length()==0)throw new IOException("Existing model is empty or invalid; preserved: "+dest);return;}
  if(tmp.exists()&&!tmp.delete())throw new IOException("Cannot remove interrupted model: "+tmp);
  if(dir.getUsableSpace()<size+16L*1024*1024)throw new IOException("Insufficient storage for "+name);
  try {
   MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
   try(InputStream in=source.open(name);FileOutputStream out=new FileOutputStream(tmp)){
    byte[] buf=new byte[256*1024];int n;while((n=in.read(buf))!=-1){count+=n;if(count>size)throw new IOException("Oversized asset: "+name);out.write(buf,0,n);digest.update(buf,0,n);}out.getFD().sync();
   }
   StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));
   if(count!=size||!hex.toString().equals(hash))throw new IOException("Incomplete or corrupt asset: "+name);
   if(dest.exists())return;
   if(!tmp.renameTo(dest))throw new IOException("Cannot publish model: "+dest);
  }finally{if(tmp.exists())tmp.delete();}
 }
}
