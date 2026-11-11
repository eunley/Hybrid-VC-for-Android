package com.svc.voice;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 推理引擎：hubert + rmvpe 常驻，角色模型（enc/dec）只保留一个 */
public final class Engine implements AutoCloseable {

    public static final int SR_OUT = 44100;
    public static final int SR_IN = 16000;
    public static final int HOP = 512;
    public static final int SSL = 768;
    public static final int INTER = 192;
    public static final float NOISE_SCALE = 0.35f;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession.SessionOptions opts6, opts2;
    /** QNN(NPU) 是否可用；后端名，null=不可用 */
    /** enable_qnn 内容是否 gpu */
    /** 模型目录（开关文件都放这里） */
    private File modelsRoot;
    private OrtSession hubert, rmvpe, fcpe;
    private boolean useFcpe = false;
    private OrtSession enc, dec;
    /** qnn_strict 开关（只对首次编译 dec 生效） */
    private Feats feats;

    public long tHubert, tRmvpe, tMel, tEnc, tDec, tOther, tAll;
    /** 细粒度打点：下标 0..7 的毫秒值 */
    public int hubertRetry = 0;
    /** hubert 能接受的输入长度（缓存，避免每轮试探） */
    private int hubertFixLen = 0;
    public String hubertErr = null;
    private String roleName;
    /** ★ 常驻缓存：当前已加载的角色目录 + 分片号，用于跳过重复加载 */
    private String cachedDir;
    private int cachedPick = -1;
    /** ★ 进程级单例：hubert/rmvpe/enc/dec 全部常驻，跨任务复用 */
    private static Engine CACHED;
    private static String CACHE_KEY;
    private int frames;          // 当前角色 enc 的固定帧数
    private List<Integer> frameOptions = new ArrayList<>();
    private String roleDir;

    private float[] bufEps, bufRandSine;

    public interface Log { void log(String s); }
    private Log log = s -> {};

    /**
     * 把一个模型现场编译成 HTP 机器码（EPContext）。
     * @param modelFile 要编译的 onnx
     * @param ctxFile   输出路径（正常会生成它，或它同名的 .bin/.data）
     * @param pick      T 帧数（静态图可传 0）
     * @return 编译耗时 ms；缓存命中返回 0
     */
    public static long compileModel(File modelFile, File ctxFile, int pick, Log log) throws Exception {
        if (validContext(ctxFile, modelFile.getName(), pick)) {
            log.log("已编译过，跳过");
            return 0;
        }
        if (!modelFile.isFile()) throw new Exception("找不到模型：" + modelFile);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions q = new OrtSession.SessionOptions();
        try {
            q.setIntraOpNumThreads(Tune.threads());
            q.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            try { q.setCPUArenaAllocator(false); } catch (Throwable ignore) {}
            if (pick > 0) {
                try { q.setSymbolicDimensionValue("T", pick); } catch (Throwable ignore) {}
                try { q.setSymbolicDimensionValue("Tn", (long) pick * HOP); } catch (Throwable ignore) {}
            }
            try { q.setSymbolicDimensionValue("batch_size", 1); } catch (Throwable ignore) {}
            if (pick > 0 && modelFile.getName().equals("hubert.onnx")) q.setSymbolicDimensionValue("sequence_length", pick);
            if (pick > 0 && modelFile.getName().equals("rmvpe.onnx")) q.setSymbolicDimensionValue("time", pick);
            // 详细日志：要看得到 QNN 报告「哪些节点不支持」
            try {
                Class<?> lvlCls = Class.forName("ai.onnxruntime.OrtLoggingLevel");
                Object verbose = null;
                for (Object o : lvlCls.getEnumConstants())
                    if (String.valueOf(o).contains("VERBOSE")) verbose = o;
                q.getClass().getMethod("setSessionLogLevel", lvlCls).invoke(q, verbose);
                q.setSessionLogVerbosityLevel(3);
                log.log("ORT 日志级别已设为 VERBOSE");
            } catch (Throwable t) { log.log("开 VERBOSE 日志失败：" + t); }
            java.util.Map<String, String> qo = new java.util.HashMap<String, String>();
            qo.put("backend_type", "htp");
            qo.put("htp_performance_mode", "burst");
            qo.put("htp_graph_finalization_optimization_mode", "3");
            qo.put("enable_htp_fp16_precision", "1");
            File qbase = ctxFile.getParentFile() != null
                    ? ctxFile.getParentFile().getParentFile() : ctxFile.getParentFile();
            if (qbase != null) {
                File optF = new File(qbase, "qnn_opts.txt");
                if (optF.isFile()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(optF));
                    String ln;
                    while ((ln = br.readLine()) != null) {
                        ln = ln.trim();
                        if (ln.isEmpty() || ln.startsWith("#")) continue;
                        int eq = ln.indexOf(61);
                        if (eq <= 0) continue;
                        qo.put(ln.substring(0, eq).trim(), ln.substring(eq + 1).trim());
                    }
                    br.close();
                }
            }
            // ★ 这几个是 SessionOptions 的 config entry，绝不能放 provider options（放错会被静默忽略）
            Class<?> soCls = Class.forName("ai.onnxruntime.OrtSession$SessionOptions");
            java.lang.reflect.Method addCfg = soCls.getMethod("addConfigEntry", String.class, String.class);
            addCfg.invoke(q, "ep.context_enable", "1");
            addCfg.invoke(q, "ep.context_file_path", ctxFile.getAbsolutePath());
            addCfg.invoke(q, "ep.context_embed_mode", "1");
            // Mixed execution is intentional; validated EPContext nodes prove QNN participation.
            Class.forName("ai.onnxruntime.OrtSession$SessionOptions")
                    .getMethod("addQnn", java.util.Map.class).invoke(q, qo);
            log.log("QNN 编译源="+modelFile+" shape="+pick+" 参数：" + qo);
            long t0 = System.currentTimeMillis();
            final java.io.File cf = ctxFile;
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            Thread hb = new Thread(() -> {
                int n = 0;
                while (!done.get()) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                    if (done.get()) return;
                    n += 3;
                    long sz = cf.isFile() ? cf.length() : 0;
                    log.log("  …编译中 " + n + "s" + (sz > 0 ? ("，已写 " + (sz >> 20) + "MB") : ""));
                }
            });
            hb.setDaemon(true);
            hb.start();
            // 先不直接抛：QNN 在 strict 模式下可能已经把 context 写完了才报错
            OrtSession s = null;
            Throwable createErr = null;
            try {
                s = env.createSession(modelFile.getAbsolutePath(), q);
            } catch (Throwable e) {
                createErr = e;
            } finally {
                done.set(true);
                hb.interrupt();
                try { hb.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (s != null) { try { s.close(); } catch (Throwable ignore) {} }
            long ms = System.currentTimeMillis() - t0;
            if (createErr != null) throw new RuntimeException("编译失败（产物不代表成功）：" + createErr, createErr);
            if (!validContext(ctxFile, modelFile.getName(), pick))
                throw new Exception("缓存验证失败：需要 EPContext 子图、QNN 加载及匹配的输入 shape");
            log.log("缓存验证通过：包含 QNN EPContext；允许混合 CPU/QNN 执行，未测性能。" );
            return ms;
        } finally {
            try { q.close(); } catch (Throwable ignore) {}
        }
    }

    /** 目录里的文件名集合，用来做编译前后对比 */
    /** Parse ONNX protobuf nodes instead of guessing from file size or sidecars. */
    private static long varint(byte[] b, int[] p) throws Exception {
        long v=0; for(int k=0;k<64;k+=7) { if(p[0]>=b.length) throw new Exception("truncated protobuf"); int x=b[p[0]++]&255; v|=(long)(x&127)<<k; if((x&128)==0)return v; } throw new Exception("varint");
    }
    private static boolean contextField(byte[] b, int depth) throws Exception {
        int[] p={0};
        while(p[0]<b.length) {
            int key=(int)varint(b,p), f=key>>>3, wire=key&7;
            if(wire==0) varint(b,p);
            else if(wire==1)p[0]+=8;
            else if(wire==5)p[0]+=4;
            else if(wire==2) {
                int n=(int)varint(b,p); if(n<0 || n>b.length-p[0])throw new Exception("length");
                byte[] v=java.util.Arrays.copyOfRange(b,p[0],p[0]+n); p[0]+=n;
                if(depth==2 && f==4 && "EPContext".equals(new String(v,java.nio.charset.StandardCharsets.UTF_8)))return true;
                if((depth==0 && f==7 || depth==1 && f==1) && contextField(v,depth+1))return true;
            } else throw new Exception("wire");
        } return false;
    }
    public static boolean isEpContext(File f) {
        try { return f!=null && f.isFile() && contextField(java.nio.file.Files.readAllBytes(f.toPath()),0); }
        catch(Throwable t){return false;}
    }
    public static OrtSession openContext(File f, String model, int length) throws Exception {
        if(!isEpContext(f))throw new Exception("没有 EPContext 节点："+f);
        try(OrtSession.SessionOptions o=new OrtSession.SessionOptions()) {
            o.setIntraOpNumThreads(Tune.threads());
            Map<String,String> q=new HashMap<>(); q.put("backend_type","htp"); q.put("enable_htp_fp16_precision","1"); q.put("htp_performance_mode","burst"); o.addQnn(q);
            OrtSession session=OrtEnvironment.getEnvironment().createSession(f.getAbsolutePath(),o);
            try {
                if(model.equals("hubert.onnx")) {
                    checkInput(session,"source",new long[]{1,length},OnnxJavaType.FLOAT);
                    checkInput(session,"padding_mask",new long[]{1,length},OnnxJavaType.BOOL);
                } else if(model.equals("rmvpe.onnx"))checkInput(session,"input",new long[]{1,128,length},OnnxJavaType.FLOAT);
                return session;
            } catch(Exception e){session.close();throw e;}
        }
    }
    private static void checkInput(OrtSession s,String name,long[] shape,OnnxJavaType type)throws Exception {
        ai.onnxruntime.NodeInfo n=s.getInputInfo().get(name);
        if(n==null || !(n.getInfo() instanceof ai.onnxruntime.TensorInfo))throw new Exception("缺少输入 "+name);
        ai.onnxruntime.TensorInfo t=(ai.onnxruntime.TensorInfo)n.getInfo();
        if(t.type!=type || !java.util.Arrays.equals(t.getShape(),shape))throw new Exception("shape/type 不匹配："+name+" "+t);
    }
    private static boolean validContext(File f,String model,int length) {
        if(!f.isFile())return false;
        try(OrtSession s=openContext(f,model,length)){return true;}catch(Throwable t){return false;}
    }
    private boolean decQnn, ranDec, ranBaseQnn;
    private String backendReport="";
    public void beginBackendReport(){ranDec=false;ranBaseQnn=false;backendReport="";log.log("NPU加速：等待实际推理后端确认");}
    private void reportBackend(){String state=(decQnn&&ranDec)||ranBaseQnn ? "NPU加速已启用（QNN session 执行成功，允许 CPU 混合执行）" : "NPU加速未启用（当前实际执行 CPU）";if(!state.equals(backendReport)){backendReport=state;log.log(state);}}
    private OrtSession.Result runDecoder(Map<String,OnnxTensor> inputs)throws Exception{OrtSession.Result result=dec.run(inputs);ranDec=true;log.log("stage=dec T="+frames+" successful_session="+(decQnn?"QNN":"CPU"));reportBackend();return result;}
    private OrtSession.Result runBase(String name,int length,OrtSession cpu,Map<String,OnnxTensor> inputs)throws Exception {
        OrtSession selected=baseSession(name,length,cpu);
        try{OrtSession.Result result=selected.run(inputs);log.log("stage="+name+" shape="+length+" successful_session="+(selected!=cpu?"QNN":"CPU"));if(selected!=cpu){ranBaseQnn=true;reportBackend();}return result;}catch(Exception e){
            if(selected==cpu)throw e;
            log.log(name+":"+length+" QNN执行失败，明确回退CPU："+e);
            baseQnnSessions.remove(name);baseQnnLengths.remove(name);selected.close();
            ranBaseQnn=false;OrtSession.Result result=cpu.run(inputs);reportBackend();return result;
        }
    }
    private final Map<Integer,Integer> hubertLengths=new HashMap<>();
    private final Map<String,OrtSession> baseQnnSessions=new HashMap<>();
    private final Map<String,Integer> baseQnnLengths=new HashMap<>();
    private final java.util.Set<String> baseMessages=new java.util.HashSet<>();
    private OrtSession baseSession(String name,int length,OrtSession cpu) {
        if(!npu || name.equals("hubert")) return cpu;
        String key=name+":"+length;
        if(baseQnnLengths.containsKey(name) && baseQnnLengths.get(name)==length)return baseQnnSessions.get(name);
        File f=new File(modelsRoot,name+"_ctx_"+length+".onnx");
        if(f.isFile())try {
            OrtSession previous=baseQnnSessions.remove(name); baseQnnLengths.remove(name); if(previous!=null)previous.close();
            OrtSession next=openContext(f,name+".onnx",length);
            baseQnnSessions.put(name,next); baseQnnLengths.put(name,length);
            if(baseMessages.add(key))log.log(key+"：已验证 QNN EPContext，混合执行（CPU 边界是否存在取决于图划分）");
            return next;
        }catch(Throwable e){if(baseMessages.add(key))log.log(key+"：QNN缓存拒绝，明确回退 CPU："+e);}
        else if(baseMessages.add(key))log.log(key+"：无匹配缓存，CPU");
        return cpu;
    }

    /**
     * 诊断：用「允许回退 CPU」的方式建 session，只为让 ORT 打印每个节点分给了哪个 EP。
     * 不做编译，几秒就返回。不支持的节点会在 logcat 里以 onnxruntime tag 出现。
     */
    public static int readStaticT(File onnx) {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = null;
        OrtSession s = null;
        try {
            o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(1);
            s = env.createSession(onnx.getAbsolutePath(), o);
            ai.onnxruntime.NodeInfo ni = s.getInputInfo().get("z");
            if (ni == null) return -1;
            ai.onnxruntime.TensorInfo ti = (ai.onnxruntime.TensorInfo) ni.getInfo();
            long[] shp = ti.getShape();
            if (shp == null || shp.length < 3) return -1;
            long v = shp[2];
            return v > 0 ? (int) v : -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignore) {}
            try { if (o != null) o.close(); } catch (Throwable ignore) {}
        }
    }

    /** 读 ctx 图里 z 的实际帧数，拿不到返回 -1（用来校正文件名） */
    public static int readCtxT(File ctxOnnx) {
        return readStaticT(ctxOnnx);
    }

    public static long compileNpuContext(File roleDir, int pick, Log log) throws Exception {
        File dec = new File(roleDir, "dec.onnx");
        if (!dec.isFile()) throw new Exception("角色目录里没有 dec.onnx");
        if (isEpContext(dec)) {
            log.log("这个角色的 dec.onnx 已经是编译好的 NPU 图，不用再编（也不支持换档位）");
            return 0;
        }
        // 静态图的话，T 是写死的，我们传的 pick 无效，会编出「名字错、shape 对不上」的 ctx。
        // 先把真实 T 读出来，覆盖 pick。
        int realT = readStaticT(dec);
        if (realT > 0 && realT != pick) {
            log.log("★ 该角色 dec.onnx 是静态图，T 固定为 " + realT
                    + " 帧（" + Prefs.framesToMs(realT) + "ms），忽略设置的 " + pick + " 帧");
            pick = realT;
        }
        File ctx = new File(roleDir, "dec_ctx_" + pick + ".onnx");
        long ms = compileModel(dec, ctx, pick, log);
        // 兜底校正：万一 QNN 输出的 T 和预期不符，按 ctx 里真实的 T 改名
        int actT = readCtxT(ctx);
        if (actT > 0 && actT != pick) {
            File fixed = new File(roleDir, "dec_ctx_" + actT + ".onnx");
            if (ctx.renameTo(fixed))
                log.log("★ 校正档位名：dec_ctx_" + pick + " → dec_ctx_" + actT + ".onnx");
        }
        return ms;
    }

    /** 列出角色目录下已编译好的最大分片帧数（升序） */
    public static java.util.List<Integer> listCompiled(File roleDir) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<Integer>();
        File[] fs = roleDir.listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("dec_ctx_") || !n.endsWith(".onnx")) continue;
            if (f.length() <= 1024) continue;
            try {
                out.add(Integer.parseInt(n.substring(8, n.length() - 5)));
            } catch (Throwable ignore) {}
        }
        java.util.List<Integer> r = new java.util.ArrayList<Integer>();
        r.addAll(out);
        return r;
    }

    public static volatile String device = "cpu";
    private final boolean npu = "npu".equals(device);
    public Engine(String modelsDir, Log log) throws Exception {
        this.log = log == null ? x -> {} : log;
        BundledModels.ensureReady(new File(modelsDir), this.log);
        modelsRoot = new File(modelsDir);
        opts6 = new OrtSession.SessionOptions(); opts2 = new OrtSession.SessionOptions();
        for (OrtSession.SessionOptions o : new OrtSession.SessionOptions[]{opts6,opts2}) {
            o.setIntraOpNumThreads(Tune.threads());
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            o.setCPUArenaAllocator(false); o.setMemoryPatternOptimization(false);
        }
        long originalAffinity=Tune.pin(Tune.mask());
        try {
            feats = new Feats(new File(modelsDir,"mel.bin").getAbsolutePath());
            hubert = env.createSession(new File(modelsDir,"hubert.onnx").getAbsolutePath(),opts6);
            rmvpe = env.createSession(new File(modelsDir,"rmvpe.onnx").getAbsolutePath(),opts6);
            log.log("常驻基础模型：Hubert CPU / RMVPE CPU pid="+android.os.Process.myPid());
            if(npu) baseSession("rmvpe",352,rmvpe);
        } catch(Exception e) { close(); throw e; } finally {Tune.unpin(originalAffinity);}
    }

    public int frames() { return frames; }
    public String roleName() { return roleName; }
    public List<Integer> frameOptions() { return Collections.unmodifiableList(frameOptions); }

    /** 切换角色：先释放旧角色的 enc/dec，只保留一个 */
    public synchronized void loadRole(String dir, int wantFrames) throws Exception {
        File d = new File(dir);
        List<Integer> ts = new ArrayList<>();
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith("enc_") && n.endsWith(".onnx")) {
                    try { ts.add(Integer.parseInt(n.substring(4, n.length() - 5))); }
                    catch (NumberFormatException ignore) {}
                }
            }
        }
        Collections.sort(ts);

        // ★ 动态 enc：有 enc.onnx 就无视档位，任意帧数都能用
        boolean dynEnc = new File(d, "enc.onnx").exists();
        if (!dynEnc && ts.isEmpty())
            throw new Exception("角色目录没有 enc*.onnx：" + dir);
        if (!new File(d, "dec.onnx").exists()) throw new Exception("角色目录缺少 dec.onnx");

        int pick;
        if (dynEnc) {
            pick = wantFrames;               // 动态图：直接用用户要的帧数
        } else {
            pick = ts.get(0);
            for (int t : ts) if (Math.abs(t - wantFrames) < Math.abs(pick - wantFrames)) pick = t;
        }

        if (enc != null && dec != null
                && d.getAbsolutePath().equals(cachedDir) && pick == cachedPick) {
            log.log("复用已加载的角色 " + d.getName() + " 分片 "
                    + Math.round(pick * HOP * 1000f / SR_OUT) + "ms（未重新加载）");
            return;
        }

        closeRole();
        cachedDir = d.getAbsolutePath();
        cachedPick = pick;
        int wantMs = Math.round(wantFrames * HOP * 1000f / SR_OUT);
        int gotMs = Math.round(pick * HOP * 1000f / SR_OUT);
        StringBuilder sb = new StringBuilder();
        for (int t : ts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(Math.round(t * HOP * 1000f / SR_OUT));
        }
        if (dynEnc) {
            log.log("动态 enc（任意帧数）");
        } else if (wantFrames != pick) {
            log.log("注意：你设的分片 " + wantMs + "ms 没有对应模型，已用最接近的 " + gotMs
                    + "ms（本角色可选：" + sb + "ms）");
        }
        log.log("加载角色 " + d.getName() + " 分片 " + gotMs + "ms"
                );
        // 角色 session 的线程池同样在 createSession 时创建，这里也按设置的核心 pin 一次
        long pinOrig = Tune.pin(Tune.mask());
        try {
            File encFile = dynEnc ? new File(d, "enc.onnx")
                                  : new File(d, "enc_" + pick + ".onnx");
            enc = env.createSession(encFile.getAbsolutePath(), opts2);
            File ctxFile = new File(d,"dec_ctx_"+pick+".onnx");
            if(npu && (ctxFile.isFile() || isEpContext(new File(d,"dec.onnx")))) {
                if(!ctxFile.isFile()) ctxFile=new File(d,"dec.onnx");
                try {
                    OrtSession candidate = openContext(ctxFile,"dec.onnx",pick);
                    try {
                        checkInput(candidate,"z",new long[]{1,INTER,pick},OnnxJavaType.FLOAT);
                        checkInput(candidate,"har",new long[]{1,1,(long)pick*HOP},OnnxJavaType.FLOAT);
                        checkInput(candidate,"cond",new long[]{1,512,1},OnnxJavaType.FLOAT);
                        dec=candidate;decQnn=true;
                    } catch(Exception e) {candidate.close();throw e;}
                    log.log("角色 dec NPU 缓存驻留 T="+pick);
                } catch(Exception e) {log.log("dec 缓存拒绝，CPU："+e);}
            }
            if(dec==null) {
                File source=new File(d,"dec.onnx");
                if(isEpContext(source)) source=new File(d,"_cpu_bak/dec.onnx");
                if(!source.isFile() || isEpContext(source)) throw new Exception("缺少可用于 CPU 的原始 dec 模型");
                dec=env.createSession(source.getAbsolutePath(),opts2);decQnn=false;
                log.log("角色 dec CPU 驻留 T="+pick);
            }
        } catch(Exception e) {closeRole();throw e;} finally {
            Tune.unpin(pinOrig);
        }
        frames = pick;
        frameOptions = ts;
        roleName = d.getName();
        roleDir = dir;
        bufEps = new float[INTER * frames];
        bufRandSine = new float[(long) frames * HOP * 9 <= Integer.MAX_VALUE
                ? frames * HOP * 9 : 0];
    }

    private void closeRole() {
        try { if (enc != null) enc.close(); } catch (Exception ignore) {}
        try { if (dec != null) dec.close(); } catch (Exception ignore) {}
        enc = null; dec = null; cachedDir=null; cachedPick=-1;
    }

    /** 任务开始前清零统计（由 Pipeline.process 调用一次） */
    public int completedChunks;
    public void resetStats() {
        completedChunks=0;
        tHubert = tRmvpe = tMel = tEnc = tDec = tOther = 0;
    }

    /** 处理一片 16k 单声道音频，返回 44.1k 音频（长度 = n_out*512） */
    private Enc encode0(float[] wav16, float[] ctx) throws Exception { return encode0(wav16,ctx,0); }
    private Enc encode0(float[] wav16, float[] ctx, int semitones) throws Exception {
        long _tAll = System.nanoTime();
        // ★ 不再每片清零：由 Pipeline.process 入口统一清零，否则日志只剩最后一片
        int T = frames;
        // 1) hubert（对长度敏感，需要 ±32 试探）
        float[] c = null;
        int used = 0;
        int T0 = wav16.length;
        hubertFixLen = hubertLengths.containsKey(T0) ? hubertLengths.get(T0) : 0;
        // 实时模式下每轮 wav16 长度固定，这里缓存上次成功的长度，
        // 从第 2 轮起就不再走 ±32 试探（原来每轮白跑 4 次 hubert）
        if (hubertFixLen > 0) {
            float[] y = new float[hubertFixLen];
            System.arraycopy(wav16, 0, y, 0, Math.min(hubertFixLen, wav16.length));
            try {
                long _t = System.nanoTime();
                c = hubert(y);
                tHubert += System.nanoTime() - _t;
                used = hubertFixLen;
            } catch (Exception e) {
                hubertFixLen = 0;
            }
        }
        outer:
        for (int d = 0; used == 0 && d < 320 * 16; d += 32) {
            if (d > 0) hubertRetry++;
            int[] cands = d == 0 ? new int[]{T0} : new int[]{T0 + d, T0 - d};
            for (int Tc : cands) {
                if (Tc < 3200) continue;
                float[] y = new float[Tc];
                System.arraycopy(wav16, 0, y, 0, Math.min(Tc, wav16.length));
                try {
                    long _t = System.nanoTime();
                    c = hubert(y);
                    tHubert += System.nanoTime() - _t;
                    used = Tc;
                    hubertFixLen = Tc; hubertLengths.put(T0,Tc); // 按原始长度缓存，末片独立
                    break outer;
                } catch (Exception e) {
                    if (hubertErr == null) hubertErr = Tc + ": " + e;
                }
            }
        }
        if (used == 0) throw new Exception("hubert 找不到可用长度");
        c = hubertCache;
        int Tc = used;
        if (hubertFixLen == 0) hubertFixLen = Tc;
        float[] f0wav = wav16;
        if (ctx != null && ctx.length > 0) {
            f0wav = new float[ctx.length + wav16.length];
            System.arraycopy(ctx, 0, f0wav, 0, ctx.length);
            System.arraycopy(wav16, 0, f0wav, ctx.length, wav16.length);
        }
        long _tr = System.nanoTime();
        float[] f0_all;
        if (useFcpe) {
            try (OnnxTensor ta = fTensor(f0wav, 1, f0wav.length, 1); OrtSession.Result r = fcpe.run(Collections.singletonMap("audio", ta))) {
                f0_all = readAll((OnnxTensor) r.get(0));
            }
            for (int i=0; i<f0_all.length; i++) if (!Float.isFinite(f0_all[i]) || f0_all[i] < 0f) f0_all[i]=0f;
        } else {
            int[] nf = new int[1];
            long _tm = System.nanoTime();
            float[] mel = feats.mel(f0wav, nf);
            tMel += System.nanoTime() - _tm;
            int mf = nf[0];
            int mfPad = mf + ((32 - mf % 32) % 32);
            float[] in = mel;
            if (mfPad != mf) { in = new float[feats.nMels * mfPad]; for (int m=0; m<feats.nMels; m++) System.arraycopy(mel,m*mf,in,m*mfPad,mf); }
            float[] sal; int nbin;
            try (OnnxTensor t=fTensor(in,1,feats.nMels,mfPad); OrtSession.Result r=runBase("rmvpe",mfPad,rmvpe,Collections.singletonMap("input",t))) {
                OnnxTensor o=(OnnxTensor)r.get(0); nbin=(int)o.getInfo().getShape()[2];
                FloatBuffer fb=o.getFloatBuffer(); fb.rewind(); int avail=fb.remaining(); float[] all=new float[avail]; fb.get(all);
                sal=new float[nbin*mf]; int copy=Math.min(mf,avail/Math.max(nbin,1)); if(copy>0) System.arraycopy(all,0,sal,0,copy*nbin);
            }
            f0_all=Feats.rmvpeF0(sal,nbin,mf);
        }
        int want=Math.max(1,(int)Math.round((double)wav16.length/(SR_IN/100.0)));
        float[] f0_10ms;
        if(f0_all.length>want) { f0_10ms=new float[want]; System.arraycopy(f0_all,f0_all.length-want,f0_10ms,0,want); } else f0_10ms=f0_all;
        if(OCTAVE_FIX && !useFcpe) octaveFix(f0_10ms);
        int nn=0; for(float v:f0_10ms) if(v>0) nn++;
        if(nn>0) { float[] q=new float[nn]; int k=0; for(float v:f0_10ms) if(v>0) q[k++]=v; java.util.Arrays.sort(q); lastF0Med=q[nn/2]; lastF0Voiced=nn*100/f0_10ms.length; }
        tRmvpe += System.nanoTime() - _tr;
        // 3) 帧数对齐
        int nOut = Math.max(1, (int) Math.round((double) Tc / SR_IN * SR_OUT / HOP));
        if (nOut > T) nOut = T;
        long _to = System.nanoTime();
        int cT = c.length / SSL;
        float[] cInterp = Feats.interpChannels(c, SSL, cT, nOut);
        float[] f0i = Feats.interpNorm(f0_10ms, nOut);

        // 4) enc（补零到固定 T 帧）
        float[] cPad = new float[SSL * T];
        for (int k = 0; k < SSL; k++)
            System.arraycopy(cInterp, k * nOut, cPad, k * T, nOut);
        if(semitones!=0) { double factor=Math.pow(2.0,semitones/12.0); for(int i=0;i<f0i.length;i++) if(f0i[i]>0) f0i[i]*=factor; }
        float[] f0Pad = new float[T];
        System.arraycopy(f0i, 0, f0Pad, 0, nOut);
        float[] uv = new float[T];
        for (int i = 0; i < T; i++) uv[i] = f0Pad[i] > 0 ? 1f : 0f;

        // 每片都必须重新采样噪声：固定噪声会让谐波细节重复，产生周期性 artifact
        float[] eps = bufEps;
        for (int i = 0; i < eps.length; i++) eps[i] = gaussFast();
        float[] ri = new float[9];
        for (int i = 1; i < 9; i++) ri[i] = (rndNext() >>> 8) / 16777216f;
        ri[0] = 0f;
        float[] rs = bufRandSine;
        for (int i = 0; i < rs.length; i++) rs[i] = gaussFast();

        float[] z, har, cond;
        Map<String, OnnxTensor> ins = new HashMap<>();
        try (OnnxTensor tc = fTensor(cPad, 1, SSL, T);
             OnnxTensor tf0 = fTensor(f0Pad, 1, T);
             OnnxTensor tuv = fTensor(uv, 1, T);
             OnnxTensor tg = lTensor(new long[]{0}, 1, 1);
             OnnxTensor te = fTensor(eps, 1, INTER, T);
             OnnxTensor tri = fTensor(ri, 1, 9);
             OnnxTensor trs = fTensor(rs, 1, T * HOP, 9)) {
            ins.put("c", tc); ins.put("f0", tf0); ins.put("uv", tuv); ins.put("g", tg);
            ins.put("eps", te); ins.put("rand_ini", tri); ins.put("rnd_sine", trs);
            long _te = System.nanoTime();
            try (OrtSession.Result r = enc.run(ins)) {
                z = readAll((OnnxTensor) r.get(0));
                har = readAll((OnnxTensor) r.get(1));
                cond = readAll((OnnxTensor) r.get(2));
            }
            tEnc += System.nanoTime() - _te;
        }

        tOther += System.nanoTime() - _to;

        Enc e = new Enc();
        e.z = z;
        e.har = har;
        e.cond = cond;
        e.nOut = nOut;
        e.T = T;
        return e;
    }

    private float[] decode0(Enc e) throws Exception {
        long _tAll = System.nanoTime();
        float[] z = e.z, har = e.har, cond = e.cond;
        int nOut = e.nOut, T = e.T;
        // 5) dec（裁剪到 nOut 帧）
        // ★ 固定按 T 帧喂 dec（补零），算完再裁回 nOut。
        //   原因：QNN(NPU) 要求静态 shape；nOut 可变的动态输入会让 QNN 整图回退 CPU。
        //   补零区域裁掉；非因果卷积仍可能受边界影响。
        float[] zc = new float[INTER * T];
        for (int k = 0; k < INTER; k++)
            System.arraycopy(z, k * T, zc, k * T, nOut);
        float[] hc = new float[T * HOP];
        System.arraycopy(har, 0, hc, 0, nOut * HOP);
        long _td = System.nanoTime();
        try (OnnxTensor tz = fTensor(zc, 1, INTER, T);
             OnnxTensor th = fTensor(hc, 1, 1, (long) T * HOP);
             OnnxTensor tcond = fTensor(cond, 1, 512, 1);
             OrtSession.Result r = runDecoder(map3(tz, th, tcond))) {
            long _t2 = System.nanoTime();
            tDec += _t2 - _td;             // 真正的 dec.run（必须累加，之前是覆盖导致日志骗人）
            float[] _o = readAll((OnnxTensor) r.get(0));
            tOther += System.nanoTime() - _t2;  // 输出拷贝
            tAll = System.nanoTime() - _tAll;
            if (nOut < T) {
                float[] cut = new float[nOut * HOP];
                System.arraycopy(_o, 0, cut, 0, cut.length);
                _o = cut;
            }
            return _o;
        }
    }

    /** Output coordinates remain in the original full-window sample domain. */
    public static final class Rendered {
        public final float[] audio;
        public final int origin;
        Rendered(float[] a, int o) { audio=a; origin=o; }
        public float at(int sample) {
            int i=sample-origin;
            if(i<0 || i>=audio.length) throw new IllegalArgumentException("decoder crop index="+sample+" origin="+origin+" length="+audio.length);
            return audio[i];
        }
    }

    /** Keep full upstream encoding. Preserve newest hop for next-window blending. */
    public Rendered decodeLive(Enc e, int firstSample) throws Exception {
        synchronized(decLock) {
            long[] shape=((ai.onnxruntime.TensorInfo)dec.getInputInfo().get("z").getInfo()).getShape();
            int a=Math.max(0, Math.floorDiv(firstSample,HOP)-12);
            // A static CPU graph or QNN context must keep its validated input shape.
            if(decQnn || shape.length!=3 || shape[2]>0 || a==0 || e.nOut!=e.T) {
                log.log("live-dec mode=full backend="+(decQnn?"QNN":"CPU")+" T="+e.T);
                return new Rendered(decode0(e),0);
            }
            int t=e.T-a;
            float[] z=new float[INTER*t];
            for(int k=0;k<INTER;k++) System.arraycopy(e.z,k*e.T+a,z,k*t,t);
            float[] h=java.util.Arrays.copyOfRange(e.har,a*HOP,e.T*HOP);
            long start=System.nanoTime();
            try(OnnxTensor tz=fTensor(z,1,INTER,t);
                OnnxTensor th=fTensor(h,1,1,(long)t*HOP);
                OnnxTensor tc=fTensor(e.cond,1,512,1);
                OrtSession.Result r=dec.run(map3(tz,th,tc))) {
                float[] y=readAll((OnnxTensor)r.get(0));
                if(y.length!=t*HOP) throw new IllegalStateException("dynamic decoder output length="+y.length);
                tDec+=System.nanoTime()-start;ranDec=true;reportBackend();
                log.log("live-dec mode=crop backend=CPU T="+t+" origin="+(a*HOP)+" halo=12 upstream="+e.T);
                return new Rendered(y,a*HOP);
            }
        }
    }

    private static Map<String, OnnxTensor> map3(OnnxTensor a, OnnxTensor b, OnnxTensor c) {
        Map<String, OnnxTensor> m = new HashMap<>();
        m.put("z", a); m.put("har", b); m.put("cond", c);
        return m;
    }

    // ---- 快速高斯：原来每轮要 12 万个 nextGaussian（log/sqrt），是 tOther 的大头 ----
    private static final float[] GTAB = new float[1 << 16];
    static {
        java.util.Random r = new java.util.Random(20260101L);
        for (int i = 0; i < GTAB.length; i++) GTAB[i] = (float) r.nextGaussian();
    }
    private int rngS = 0x9E3779B9;

    /** Explicit task boundary: identical file/live chunks use identical random draws. */
    public void resetRandom(){synchronized(encLock){rngS=0x9E3779B9;}}

    private int rndNext() {
        int x = rngS;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rngS = x;
        return x;
    }

    private float gaussFast() {
        return GTAB[(rndNext() >>> 16) & 0xFFFF];
    }

    /** 编码阶段的中间结果：可以跨线程传给解码阶段 */
    public static final class Enc {
        public float[] z;     // [INTER * T]
        public float[] har;   // [T * HOP]
        public float[] cond;  // [512]
        public int nOut;
        public int T;
    }

    private final Object encLock = new Object(), decLock = new Object();

    /** 只做编码（hubert+rmvpe+enc）；ctx = rmvpe 用的历史音频，可为 null */
    public Enc encode(float[] wav16, float[] ctx) throws Exception {
        synchronized (encLock) { return encode0(wav16, ctx); }
    }

    public Enc encode(float[] wav16) throws Exception {
        return encode(wav16, null);
    }

    /** 只做解码（dec），可以和下一块的 encode 并行 */
    public float[] decode(Enc e) throws Exception {
        synchronized (decLock) { return decode0(e); }
    }

    public float[] convert(float[] wav16, int semitones) throws Exception {
        Tune.boostThread();
        Enc e; synchronized(encLock) { e=encode0(wav16,null,Math.max(-24,Math.min(24,semitones))); }
        return decode(e);
    }

    public float[] convert(float[] wav16) throws Exception {
        Tune.boostThread();
        return decode(encode(wav16, null));
    }

    /**
     * 块间八度校正。
     * 实时每块只有 300ms，rmvpe 在短上下文下常把 F0 估成一半或两倍
     * （实测 161Hz / 86Hz 交替，正好差八度）。块与块互相不衔接，
     * 结果音高在两块之间直接跳八度 —— 听起来完全不知道在说什么。
     * 人说话 300ms 内极少跨八度，所以用块内非零 F0 中位数判断：
     * 与上一块差接近一倍就整块乘 2 或 0.5 拉回来，块内轮廓保持不变。
     */
    private double prevMedF0 = 0;
    public int octaveFixCnt = 0;

    // ★ 块间八度校正：这是给"rmvpe 只有短窗口"打的补丁。
    //   现在 rmvpe 已改为喂长上下文（encode(wav16, ctx)，约 1.25s 窗口），
    //   它自己就能判断准八度，补丁反而可能把对的改成错的，所以默认关闭。
    public static final boolean OCTAVE_FIX = false;

    /** 最近一块的 f0 中位数（Hz）与清浊帧占比（%），仅用于日志诊断 */
    public volatile float lastF0Med = 0;
    public volatile int lastF0Voiced = 0;

    public void resetF0Track() { prevMedF0 = 0; octaveFixCnt = 0; }

    private void octaveFix(float[] f0) {
        int n = 0;
        for (int i = 0; i < f0.length; i++) if (f0[i] > 0) n++;
        if (n < 3) return;
        float[] tmp = new float[n];
        int k = 0;
        for (int i = 0; i < f0.length; i++) if (f0[i] > 0) tmp[k++] = f0[i];
        java.util.Arrays.sort(tmp);
        double med = tmp[n / 2];
        if (prevMedF0 > 0) {
            double r = med / prevMedF0;
            double mul = 1;
            if (r > 1.55 && r < 2.60) mul = 0.5;
            else if (r > 0.38 && r < 0.65) mul = 2.0;
            if (mul != 1) {
                for (int i = 0; i < f0.length; i++) if (f0[i] > 0) f0[i] *= mul;
                med *= mul;
                octaveFixCnt++;
            }
        }
        prevMedF0 = med;
    }

    private float[] hubertCache;

    private float[] hubert(float[] wav) throws Exception {
        ByteBuffer bb = ByteBuffer.allocateDirect(wav.length)
                .order(ByteOrder.nativeOrder());
        bb.limit(wav.length);
        long[] shp = new long[]{1, wav.length};
        try (OnnxTensor src = fTensor(wav, 1, wav.length);
             OnnxTensor mask = OnnxTensor.createTensor(env, bb, shp, OnnxJavaType.BOOL);
             OrtSession.Result r = hubertRun(src, mask)) {
            OnnxTensor o = (OnnxTensor) r.get(0);
            long[] s = o.getInfo().getShape();
            int T = (int) s[1];
            int dim = (int) s[2];
            FloatBuffer fb = o.getFloatBuffer();
            float[] flat = new float[T * dim];
            fb.get(flat);
            float[] tr = new float[dim * T];
            for (int t = 0; t < T; t++)
                for (int k = 0; k < dim; k++)
                    tr[k * T + t] = flat[t * dim + k];
            hubertCache = tr;
            return tr;
        }
    }

    private final Map<String, OnnxTensor> hbMap = new HashMap<>();

    private OrtSession.Result hubertRun(OnnxTensor src, OnnxTensor mask) throws Exception {
        hbMap.put("source", src);
        hbMap.put("padding_mask", mask);
        return runBase("hubert", (int)src.getInfo().getShape()[1],hubert,hbMap);
    }

    private static float[] readAll(OnnxTensor o) {
        FloatBuffer fb = o.getFloatBuffer();
        float[] a = new float[fb.remaining()];
        fb.get(a);
        return a;
    }

    private OnnxTensor fTensor(float[] d, long... shape) throws Exception {
        FloatBuffer fb = ByteBuffer.allocateDirect(d.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(d).rewind();
        return OnnxTensor.createTensor(env, fb, shape);
    }

    private OnnxTensor lTensor(long[] d, long... shape) throws Exception {
        LongBuffer lb = ByteBuffer.allocateDirect(d.length * 8)
                .order(ByteOrder.nativeOrder()).asLongBuffer();
        lb.put(d).rewind();
        return OnnxTensor.createTensor(env, lb, shape);
    }

    /**
     * ★ 取常驻实例。基础模型（hubert/rmvpe，约 740MB）只在首次或有变化时加载。
     * 线程数变了必须重建（ORT 的 intra-op 线程池在 createSession 时固定）。
     */
    public static synchronized Engine acquire(String modelsDir, Log log) throws Exception {
        String key = modelsDir + "|" + Tune.threads()+"|"+Tune.mask()+"|"+device;
        if (CACHED != null && key.equals(CACHE_KEY)) {
            CACHED.log = log == null ? s -> {} : log;
            CACHED.log.log("复用常驻模型（hubert/rmvpe/enc/dec 仍在内存）");
            return CACHED;
        }
        if (CACHED != null) {
            try { CACHED.close(); } catch (Throwable ignore) {}
            CACHED = null; CACHE_KEY = null;
        }
        CACHED = new Engine(modelsDir, log);
        CACHE_KEY = key;
        return CACHED;
    }

    /** ★ 彻底释放常驻内存（切角色/改设置/退出时用） */
    public static synchronized void release() {
        if (CACHED != null) {
            try { CACHED.close(); } catch (Throwable ignore) {}
            CACHED = null; CACHE_KEY = null;
        }
    }

    @Override
    public void close() {
        closeRole();
        for(OrtSession s:baseQnnSessions.values())try{s.close();}catch(Exception ignore){}
        baseQnnSessions.clear(); baseQnnLengths.clear();
        try { if (hubert != null) hubert.close(); } catch (Exception ignore) {}
        try { if (rmvpe != null) rmvpe.close(); } catch (Exception ignore) {}
        try { if (fcpe != null) fcpe.close(); } catch (Exception ignore) {}
        try { opts6.close(); opts2.close(); } catch(Exception ignore) {}
    }
}