import com.svc.voice.ModelFiles;
import java.io.*;import java.nio.file.*;import java.security.*;
public class ModelFilesTest {
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("model-install-test").toFile();byte[] bytes="production-model".getBytes("UTF-8");StringBuilder h=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))h.append(String.format("%02x",b&255));
  ModelFiles.Source src=n->new ByteArrayInputStream(bytes);
  ModelFiles.install(dir,"a",bytes.length,h.toString(),src);
  if(!java.util.Arrays.equals(bytes,Files.readAllBytes(new File(dir,"a").toPath())))throw new AssertionError();
  Files.write(new File(dir,"a").toPath(),new byte[]{42});ModelFiles.install(dir,"a",bytes.length,h.toString(),src);if(new File(dir,"a").length()!=1)throw new AssertionError("overwrite");
  Files.write(new File(dir,"b.bundled.tmp").toPath(),new byte[]{1});
  try{ModelFiles.install(dir,"b",bytes.length,h.toString(),n->new ByteArrayInputStream(new byte[]{1}));throw new AssertionError();}catch(IOException expected){}
  if(new File(dir,"b").exists()||new File(dir,"b.bundled.tmp").exists())throw new AssertionError("partial published");
  try{ModelFiles.install(dir,"b",bytes.length,h.toString(),n->{throw new IOException("permission");});throw new AssertionError();}catch(IOException expected){}
  ModelFiles.install(dir,"b",bytes.length,h.toString(),src);
  try{ModelFiles.install(dir,"c",Long.MAX_VALUE/2,h.toString(),src);throw new AssertionError();}catch(IOException expected){}
  for(File f:dir.listFiles())f.delete();dir.delete();System.out.println("PASS clean deployment, user preservation, interrupted/failed retry, insufficient disk");
 }
}
