package com.svc.voice;

/** 分片调度 + 交叉淡化合并（对应 svc_stream.py 的切片逻辑） */
public final class Pipeline {

    public static final int SR_OUT = Engine.SR_OUT;
    public static final int SR_IN = Engine.SR_IN;
    public static final int HOP = Engine.HOP;

    public interface AudioProgress { void onAudioProgress(long processedSamples, long totalSamples); }
    public static final ThreadLocal<AudioProgress> audioProgress = new ThreadLocal<>();

    public interface Progress {
        void onProgress(int done, int total, String msg);
    }

    /** 16k 音频长度 -> 帧数 */
    public static int framesOf(int samples16) {
        return (int) Math.floor((double) samples16 * SR_OUT / HOP / SR_IN);
    }

    /** 帧边界 -> 16k 采样位置 */
    public static int sample16Of(int frame) {
        return (int) Math.round((double) frame * HOP * SR_IN / SR_OUT);
    }

    /**
     * 整段处理：分片大小 = 引擎块长(frames)，分片缓冲 = xfadeFrames（帧）
     * 返回 44.1k 音频。内存与音频长度成正比（44.1k 单声道 float，10 分钟约 105MB）。
     */
    private static int estimateChunks(int length,int frames,int fade){int full=sample16Of(frames),step=sample16Of(Math.max(1,frames-Math.max(0,Math.min(fade,frames/2))));return length==0?0:1+(int)Math.ceil(Math.max(0,length-full)/(double)step);}
    public static final ThreadLocal<Boolean> fileDenoise=new ThreadLocal<>();
    public static final ThreadLocal<Boolean> fileVolumeLimit=new ThreadLocal<>();
    public static final ThreadLocal<Integer> filePitch=new ThreadLocal<>();
    public static final ThreadLocal<Long> fileEnvelopeToken=new ThreadLocal<>();
    public static float[] process(Engine eng, float[] wav16, int xfadeFrames,
                                  Progress cb) throws Exception {
        long envelopeToken = fileEnvelopeToken.get() == null ? -1 : fileEnvelopeToken.get();
        final int semitones=filePitch.get()==null?0:filePitch.get();
        eng.resetStats();
        eng.resetRandom();
        int total=(int)Math.round(wav16.length*(double)SR_OUT/SR_IN);
        float[] acc=new float[total];
        final int[] done={0};
        ChunkPipeline core=new ChunkPipeline(eng.frames(),xfadeFrames,
            segment->{JobService.checkpoint();if(Thread.currentThread().isInterrupted()||JobService.cancelReq)throw new RuntimeException("__CANCELLED__");float[] y=eng.convert(segment,semitones);eng.completedChunks++;return y;},
            (audio,start)->System.arraycopy(audio,0,acc,(int)start,audio.length),
            new ChunkPipeline.Observer(){
                public void begin(long start,int real){}
                public void complete(long start,int real,long nanos){
                    AudioProgress audioCb=audioProgress.get();if(audioCb!=null)audioCb.onAudioProgress(Math.min(wav16.length,start+real),wav16.length);
                    if(cb!=null)cb.onProgress(++done[0],estimateChunks(wav16.length,eng.frames(),xfadeFrames),String.format(java.util.Locale.ROOT,"%.2f-%.2fs",start/(double)SR_IN,(start+real)/(double)SR_IN));
                }
            },Boolean.TRUE.equals(fileDenoise.get()),Boolean.TRUE.equals(fileVolumeLimit.get()));
        try {core.accept(wav16);core.finish();} finally {core.close();}
        // Preserve limited levels and residual background: neither mode is peak-normalized.
        if (!Boolean.TRUE.equals(fileVolumeLimit.get()) && !Boolean.TRUE.equals(fileDenoise.get())) {
        float peak = 0;
        for (float v : acc) peak = Math.max(peak, Math.abs(v));
        if (peak > 0) {
            float g = 0.95f / peak;
            for (int i = 0; i < total; i++) acc[i] *= g;
        }
        }
        OutputEnvelope.submit(OutputEnvelope.FILE,envelopeToken,acc,0,total,SR_OUT);
        return acc;
    }
}