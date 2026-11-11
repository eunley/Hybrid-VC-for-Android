package com.svc.voice;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class RoleStore {
 private final File root;
 public RoleStore(File r)throws IOException{root=r.getCanonicalFile();if(!root.isDirectory()&&!root.mkdirs())throw new IOException("roles directory");}
 public File resolve(String n)throws IOException{if(n==null||n.length()>100||n.startsWith(".")||!n.matches("[\\p{L}\\p{N}_ .-]+"))throw new IOException("Invalid role name");File f=new File(root,n);if(Files.isSymbolicLink(f.toPath())||!root.equals(f.getCanonicalFile().getParentFile()))throw new IOException("Unsafe role path");return f;}
 public List<File> list()throws IOException{List<File> a=new ArrayList<>();File[] fs=root.listFiles();if(fs!=null)for(File f:fs)try{f=resolve(f.getName());if(f.isDirectory()&&new File(f,"dec.onnx").isFile())a.add(f);}catch(IOException ignored){}Collections.sort(a,(x,y)->x.getName().compareTo(y.getName()));return a;}
 public static void deleteTree(File f)throws IOException{if(f==null)return;Path p=f.toPath();if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return;if(!Files.isSymbolicLink(p)&&Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){File[] fs=f.listFiles();if(fs==null)throw new IOException("Cannot list directory");for(File c:fs)deleteTree(c);}Files.delete(p);}
 public void delete(String name)throws IOException{deleteTree(resolve(name));}
}
