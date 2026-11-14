package com.svc.voice;
import java.io.*;
/** Restores missing templates from packaged assets before every import. */
public final class BundledTemplates {
 private static ModelFiles.Source source;
 public static synchronized void init(ModelFiles.Source assets) { source=assets; }
 public static synchronized void ensureReady(File dir) throws IOException {
  if(source==null)throw new IOException("Template assets not initialized");
  try {
   ModelFiles.install(dir,"dec.onnx",29135103L,"29f5eef3ce44010c285bb4c7f5693960a74dbb47a2a20cadb95cc02ff007c34f",source);
   ModelFiles.install(dir,"enc.onnx",1010249L,"bdabb6a28aed542191365553d71fb73fe3e7753c4f0ceb86ad7764a8f37f2c89",source);
   ModelFiles.install(dir,"enc.onnx.data",65150550L,"28cecc8395df2deecf8afbe9c42f77f3dd4d30b5bc9b3e980f25bed3434113f7",source);
  } catch(IOException e){throw e;} catch(Exception e){throw new IOException("Cannot restore templates",e);}
 }
}
