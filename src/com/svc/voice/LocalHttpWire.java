package com.svc.voice;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.concurrent.*;

/** One request per connection. Request files are valid only during handle/reply. */
public class LocalHttpWire implements Closeable {
 // Limits include multipart framing; field text is additionally capped at 32 KiB.
 static final long MAX=128L*1024*1024;
 public interface Handler { Reply handle(Request r) throws Exception; }
 public static class Request {
  public String method,path;
  public Map<String,String> fields=new LinkedHashMap<>();
  public Map<String,File> files=new LinkedHashMap<>();
  public File body;
 }
 public static class Reply {
  public int status; public String json; private File file; private String type;
  public Reply(int status,String json){this.status=status;this.json=json;}
  public static Reply file(File f,String type){Reply r=new Reply(200,null);r.file=f;r.type=type;return r;}
 }
 public static class Error extends Exception {
  public int status; public Error(int status,String message){super(message);this.status=status;}
 }
 final ServerSocket server; final Handler handler; final File temp;
 final byte[] authToken;
 final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
 final ThreadPoolExecutor workers;
 final ScheduledThreadPoolExecutor timer;
 final Thread acceptor; volatile boolean closed;
 public LocalHttpWire(String host,int port,File temp,Handler handler)throws IOException {
  this(host,port,temp,handler,"");
 }
 public LocalHttpWire(String host,int port,File temp,Handler handler,String token)throws IOException {
  if(!validateToken(token))throw new IllegalArgumentException("Invalid HTTP token");
  authToken=token.getBytes(StandardCharsets.US_ASCII);
  if(temp==null||handler==null)throw new NullPointerException();
  if(!temp.isDirectory()&&!temp.mkdirs())throw new IOException("Invalid temp directory");
  this.temp=temp;this.handler=handler;
  server=new ServerSocket();
  try{server.bind(new InetSocketAddress(host,port),32);}catch(IOException e){server.close();throw e;}
  ThreadFactory factory=r->{Thread t=new Thread(r,"local-http-worker");t.setDaemon(true);return t;};
  workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),factory);
  timer=new ScheduledThreadPoolExecutor(1,factory);timer.setRemoveOnCancelPolicy(true);
  acceptor=new Thread(this::accept,"local-http-accept");acceptor.setDaemon(true);acceptor.start();
 }
 /** Empty disables authentication; null is not a valid custom token. */
 public static boolean validateToken(String token){
  if(token==null||token.length()>256)return false;
  for(int i=0;i<token.length();i++)if(token.charAt(i)<33||token.charAt(i)>126)return false;
  return true;
 }
 void authenticate(Map<String,String> headers)throws Error{
  if(authToken.length==0)return;
  String value=headers.get("authorization");
  if(value==null||value.length()<7||!value.regionMatches(true,0,"Bearer ",0,7))throw new Error(401,"Unauthorized");
  String supplied=value.substring(7);
  if(!validateToken(supplied)||!MessageDigest.isEqual(authToken,supplied.getBytes(StandardCharsets.US_ASCII)))throw new Error(401,"Unauthorized");
 }
 void accept(){while(!closed){Socket s=null;ScheduledFuture<?> deadline=null;
  try{s=server.accept();s.setSoTimeout(15000);sockets.add(s);final Socket c=s;
   deadline=timer.schedule(()->quiet(c),60,TimeUnit.SECONDS);final ScheduledFuture<?> d=deadline;
   workers.execute(()->serve(c,d));
  }catch(IOException|RejectedExecutionException e){if(deadline!=null)deadline.cancel(false);if(s!=null){sockets.remove(s);quiet(s);}}
 }}
 static Error bad(String s){return new Error(400,s);}
 void serve(Socket s,ScheduledFuture<?> deadline){List<File> owned=new ArrayList<>();
  try{InputStream in=s.getInputStream();OutputStream out=new BufferedOutputStream(s.getOutputStream());Reply reply;
   try{int[] budget={0};String[] start=line(in,budget).split(" ",-1);
    if(start.length!=3||!start[0].matches("[A-Z]+")||!start[2].matches("HTTP/1\\.[01]"))throw bad("Invalid request line");
    Request r=new Request();r.method=start[0];r.path=path(start[1]);Map<String,String> h=headers(in,budget);
    authenticate(h);
    if(h.containsKey("transfer-encoding"))throw bad("Transfer-Encoding unsupported");
    long n=0;String length=h.get("content-length");
    if(length!=null){if(!length.matches("[0-9]+"))throw bad("Invalid Content-Length");try{n=Long.parseLong(length);}catch(NumberFormatException e){throw bad("Invalid Content-Length");}}
    if(n>MAX)throw bad("Body exceeds 128 MiB");
    if(r.path.equals("/api/v1/uploads")&&n>32768)throw bad("Upload metadata exceeds 32 KiB");
    if(r.path.startsWith("/api/v1/uploads/")){long cap=r.path.contains("/chunks/")?8L*1024*1024:32768;if(n>cap)throw bad("Upload endpoint body limit");}
    Limited body=new Limited(new BufferedInputStream(in,8192),n);String ct=h.get("content-type");
    if(ct!=null&&ct.split(";",2)[0].trim().equalsIgnoreCase("multipart/form-data")){
     String boundary=params(ct).get("boundary");
     if(boundary==null||boundary.length()<1||boundary.length()>70||!boundary.matches("[0-9A-Za-z'()+_,./:=? -]+")||boundary.endsWith(" "))throw bad("Invalid boundary");
     multipart(body,boundary,r,owned);
    }else{r.body=create(owned);try(OutputStream f=new BufferedOutputStream(new FileOutputStream(r.body))){copy(body,f);}}
    if(body.left!=0)throw bad("Incomplete body");deadline.cancel(false);
    reply=handler.handle(r);if(reply==null)throw new Error(500,"Empty handler reply");
   }catch(Error e){reply=error(e.status,e.getMessage());}
   catch(EOFException|SocketTimeoutException e){reply=error(400,"Incomplete or timed out request");}
   catch(Exception e){reply=error(500,"Internal server error");}
   deadline.cancel(false);write(out,reply);out.flush();
  }catch(Exception ignored){}finally{deadline.cancel(false);quiet(s);sockets.remove(s);for(File f:owned)f.delete();}
 }
 File create(List<File> owned)throws IOException{File f=File.createTempFile("wire-",".tmp",temp);owned.add(f);return f;}
 static String path(String target)throws Error{
  if(!target.startsWith("/")||target.indexOf('#')>=0)throw bad("Invalid target");
  int q=target.indexOf('?');String p=q<0?target:target.substring(0,q);
  try{
   ByteArrayOutputStream decoded=new ByteArrayOutputStream();
   for(int i=0;i<p.length();i++){
    char c=p.charAt(i);
    if(c=='%'){if(i+2>=p.length())throw new IllegalArgumentException();int a=Character.digit(p.charAt(++i),16),b=Character.digit(p.charAt(++i),16);if(a<0||b<0)throw new IllegalArgumentException();decoded.write((a<<4)|b);}
    else{if(c>127)throw new IllegalArgumentException();decoded.write(c);}
   }
   p=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
  }catch(Exception e){throw bad("Invalid URL encoding");}
  for(int i=0;i<p.length();i++)if(p.charAt(i)<32||p.charAt(i)==127||p.charAt(i)=='\\')throw bad("Invalid path");
  return p;
 }
 static String line(InputStream in,int[] budget)throws IOException,Error{
  ByteArrayOutputStream b=new ByteArrayOutputStream();
  for(;;){int c=in.read();if(c<0)throw new EOFException();if(++budget[0]>32768)throw bad("Headers exceed 32 KiB");
   if(c==13){int d=in.read();if(d<0)throw new EOFException();if(++budget[0]>32768||d!=10)throw bad("Invalid line ending");break;}
   if(c==10||c==0)throw bad("Invalid header");b.write(c);
  }return new String(b.toByteArray(),StandardCharsets.ISO_8859_1);
 }
 static Map<String,String> headers(InputStream in,int[] budget)throws IOException,Error{
  Map<String,String> h=new HashMap<>();for(;;){String l=line(in,budget);if(l.isEmpty())return h;int c=l.indexOf(':');
   if(c<1||!l.substring(0,c).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))throw bad("Invalid header");
   String k=l.substring(0,c).toLowerCase(Locale.ROOT),v=l.substring(c+1).trim();
   for(int i=0;i<v.length();i++)if((v.charAt(i)<32&&v.charAt(i)!=9)||v.charAt(i)==127)throw bad("Invalid header value");
   if(h.put(k,v)!=null)throw bad("Duplicate header");
  }
 }
 static Map<String,String> params(String text)throws Error{
  Map<String,String> p=new HashMap<>();int i=text.indexOf(';');
  while(i>=0&&i<text.length()){i++;while(i<text.length()&&Character.isWhitespace(text.charAt(i)))i++;int begin=i;
   while(i<text.length()&&text.charAt(i)!='='&&text.charAt(i)!=';')i++;
   if(i==text.length()||text.charAt(i)!='=')throw bad("Invalid MIME parameter");
   String key=text.substring(begin,i).trim().toLowerCase(Locale.ROOT);i++;StringBuilder v=new StringBuilder();
   if(i<text.length()&&text.charAt(i)=='"'){i++;boolean end=false;while(i<text.length()){char c=text.charAt(i++);if(c=='"'){end=true;break;}if(c=='\\'){if(i==text.length())throw bad("Invalid quote");c=text.charAt(i++);}v.append(c);}if(!end)throw bad("Invalid quote");while(i<text.length()&&Character.isWhitespace(text.charAt(i)))i++;if(i<text.length()&&text.charAt(i)!=';')throw bad("Invalid MIME parameter");}
   else{begin=i;while(i<text.length()&&text.charAt(i)!=';')i++;v.append(text.substring(begin,i).trim());}
   if(p.put(key,v.toString())!=null)throw bad("Duplicate MIME parameter");if(i==text.length())break;
  }return p;
 }
 void multipart(Limited in,String boundary,Request r,List<File> owned)throws Exception{
  int[] preambleBudget={0};String first;do{first=line(in,preambleBudget);}while(!first.equals("--"+boundary)&&!first.equals("--"+boundary+"--"));if(first.equals("--"+boundary+"--")){copy(in,new Sink());return;}
  if(!first.equals("--"+boundary))throw bad("Missing boundary");
  byte[] marker=("\r\n--"+boundary).getBytes(StandardCharsets.US_ASCII);int count=0,total=0;
  for(;;){if(++count>256)throw bad("Too many parts");Map<String,String> h=headers(in,new int[]{0});String cd=h.get("content-disposition");
   if(cd==null||!cd.split(";",2)[0].trim().equalsIgnoreCase("form-data"))throw bad("Invalid disposition");
   Map<String,String> p=params(cd);String name=p.get("name");if(name==null||name.isEmpty()||r.files.containsKey(name)||r.fields.containsKey(name))throw bad("Invalid or duplicate field");
   boolean file=p.containsKey("filename");File f=file?create(owned):null;ByteArrayOutputStream field=file?null:new ByteArrayOutputStream();
   OutputStream output=file?new BufferedOutputStream(new FileOutputStream(f)):field;boolean last;
   try{last=part(in,marker,output,file?MAX:32768-total);}finally{output.close();}
   if(file)r.files.put(name,f);else{total+=field.size();r.fields.put(name,new String(field.toByteArray(),StandardCharsets.UTF_8));}
   if(last){copy(in,new Sink());return;}
  }
 }
 // Sliding lookahead is bounded by boundary length; suffix bytes are validated before removal.
 static boolean part(InputStream in,byte[] marker,OutputStream out,long max)throws Exception{
  byte[] look=new byte[marker.length+4];int size=0;long written=0;
  for(;;){int c=in.read();if(c<0)throw bad("Unterminated multipart");look[size++]=(byte)c;
   for(;;){boolean prefix=true;for(int i=0;i<Math.min(size,marker.length);i++)if(look[i]!=marker[i]){prefix=false;break;}
    if(prefix&&size>=marker.length+2){int a=look[marker.length]&255,b=look[marker.length+1]&255;
     if(a==13&&b==10)return false;
     if(a==45&&b==45){int d=in.read();if(d<0)return true;if(d==13&&in.read()==10)return true;throw bad("Invalid final boundary");}
     prefix=false;
    }
    if(prefix)break;
    if(++written>max)throw bad("Field too large");out.write(look[0]&255);System.arraycopy(look,1,look,0,--size);
   }
  }
 }
 static class Sink extends OutputStream{public void write(int b){}public void write(byte[] b,int o,int n){}}
 static class Limited extends InputStream{
  final InputStream in;long left;Limited(InputStream in,long n){this.in=in;left=n;}
  public int read()throws IOException{if(left==0)return -1;int c=in.read();if(c<0)throw new EOFException();left--;return c;}
  public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;if(left==0)return -1;int n=in.read(b,off,(int)Math.min(left,len));if(n<0)throw new EOFException();left-=n;return n;}
 }
 static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
 static String quote(String s){StringBuilder b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
 static Reply error(int s,String m){if(s<400||s>599)s=500;return new Reply(s,"{\"error\":{\"code\":"+s+",\"message\":"+quote(m==null?"Error":m)+"}}");}
 static void write(OutputStream out,Reply r)throws IOException{
  if(r.status<200||r.status>599)r=error(500,"Invalid reply status");
  if(r.file!=null&&!r.file.isFile())r=error(404,"File not found");
  byte[] bytes=r.file==null?(r.json==null?"null":r.json).getBytes(StandardCharsets.UTF_8):null;
  String type=r.file==null?"application/json; charset=utf-8":r.type;if(type==null||type.contains("\r")||type.contains("\n"))type="application/octet-stream";
  long n=r.file==null?bytes.length:r.file.length();
  String reason=r.status==200?"OK":r.status==400?"Bad Request":r.status==401?"Unauthorized":r.status==404?"Not Found":r.status==409?"Conflict":r.status==500?"Internal Server Error":"Response";
  out.write(("HTTP/1.1 "+r.status+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+n+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
  if(r.file==null)out.write(bytes);else try(InputStream f=new FileInputStream(r.file)){copy(f,out);}
 }
 static void quiet(Closeable c){try{c.close();}catch(IOException ignored){}}
 public void close()throws IOException{
  closed=true;server.close();for(Socket s:sockets)quiet(s);workers.shutdownNow();timer.shutdownNow();
  try{acceptor.join(2000);if(Thread.currentThread().getName().equals("local-http-worker"))return;workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  for(Socket s:sockets)quiet(s);sockets.clear();
 }
}
