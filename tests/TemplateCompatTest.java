package com.svc.voice;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class TemplateCompatTest {
 public static void main(String[] args)throws Exception {
  for(int h=0;h<65536;h++) {
   float a=OnnxTpl.halfToFloat(h),b=Float.float16ToFloat((short)h);
   if(!(Float.isNaN(a)&&Float.isNaN(b))&&Float.floatToRawIntBits(a)!=Float.floatToRawIntBits(b))throw new AssertionError("decode "+h);
  }
  Random r=new Random(32);
  for(int i=0;i<2000000;i++) {
   float f=Float.intBitsToFloat(r.nextInt());int a=OnnxTpl.floatToHalf(f),b=Float.floatToFloat16(f)&65535;
   if(!Float.isNaN(f)&&a!=b)throw new AssertionError("encode "+f+" "+a+" "+b);
  }
  File dir=Files.createTempDirectory("tpl-test").toFile();
  BundledTemplates.init(n->new FileInputStream(new File(args[0],n)));
  BundledTemplates.ensureReady(dir);
  Files.delete(new File(dir,"dec.onnx").toPath());BundledTemplates.ensureReady(dir);
  for(File f:dir.listFiles())Files.delete(f.toPath());Files.delete(dir.toPath());
  BundledTemplates.ensureReady(dir);
  for(File f:dir.listFiles())Files.delete(f.toPath());Files.delete(dir.toPath());
  System.out.println("PASS: all 65536 half patterns, 2000000 float patterns, fresh/partial/deleted template restore");
 }
}
