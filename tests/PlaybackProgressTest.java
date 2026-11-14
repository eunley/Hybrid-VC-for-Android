import com.svc.voice.VmicProgressWire;
import java.io.*;
import java.util.Arrays;

/** Tests the ACTUAL helper encoder/counter/PCM reader against the App's production decoder. */
public final class PlaybackProgressTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static void eq(long got, long want, String why) { check(got == want, why+": "+got+" != "+want); }
    private static DataInputStream input(byte[] data, final int fragment) {
        return new DataInputStream(new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b,off,Math.min(fragment,len));
            }
        });
    }
    private static byte[] encode(int tracks, long[] written, long[] played, int[] flags) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        VmicPolicy.ProgressWire.hello(out,tracks);
        VmicPolicy.ProgressWire.frame(out,1,written,played,flags);
        return bytes.toByteArray();
    }
    private static void putInt(byte[] data, int offset, int v) {
        for(int i=0;i<4;i++) data[offset+i]=(byte)(v >>> (24-i*8));
    }
    private static void putLong(byte[] data, int offset, long v) {
        for(int i=0;i<8;i++) data[offset+i]=(byte)(v >>> (56-i*8));
    }
    private static void rejected(byte[] bytes, String why) throws Exception {
        try {new VmicProgressWire.Reader(input(bytes,1)).read();}
        catch(IOException expected) {check(true,why);return;}
        throw new AssertionError("accepted "+why);
    }
    private static void protocol() throws Exception {
        byte[] bytes=encode(2,new long[]{48000,96000},new long[]{0,24000},new int[]{0,1});
        eq(bytes.length,76,"16 handshake + 8 frame header + 52 body");
        for(int fragment=1;fragment<=bytes.length;fragment++) {
            VmicProgressWire.Frame f=new VmicProgressWire.Reader(input(bytes,fragment)).read();
            eq(f.effectiveHead48(),24000,"second track consumes; fragment="+fragment);
            eq(f.written48[1],96000,"individual per-track write ceiling");
        }
        for(int cut=0;cut<bytes.length;cut++) rejected(Arrays.copyOf(bytes,cut),"truncation "+cut);
        int[] positions={0,4,8,12,16,20,32,52};
        int[] values={0,2,16000,0,2,-1,1,2};
        for(int i=0;i<positions.length;i++) {
            byte[] bad=bytes.clone();putInt(bad,positions[i],values[i]);rejected(bad,"bad header/flag "+positions[i]);
        }
        for(int length:new int[]{0,1,51,53,Integer.MAX_VALUE,Integer.MIN_VALUE}) {
            byte[] bad=bytes.clone();putInt(bad,20,length);rejected(bad,"bad exact frame length "+length);
        }
        byte[] tooMany=bytes.clone();putInt(tooMany,12,9);rejected(tooMany,"track cap");
        byte[] negative=bytes.clone();putLong(negative,36,-1);rejected(negative,"negative writes");
        byte[] overflow=bytes.clone();putLong(overflow,64,96001);rejected(overflow,"head exceeds its track writes");
        byte[] noSeq=bytes.clone();putLong(noSeq,24,0);rejected(noSeq,"zero sequence");
        ByteArrayOutputStream chain=new ByteArrayOutputStream();
        chain.write(bytes);DataOutputStream out=new DataOutputStream(chain);
        VmicPolicy.ProgressWire.frame(out,2,new long[]{96000,144000},new long[]{0,24000},new int[]{0,1});
        VmicPolicy.ProgressWire.frame(out,3,new long[]{96000,144000},new long[]{12000,24000},new int[]{1,0});
        VmicPolicy.ProgressWire.frame(out,4,new long[]{96000,144000},new long[]{12000,24000},new int[]{0,0});
        VmicProgressWire.Reader reader=new VmicProgressWire.Reader(input(chain.toByteArray(),4096));
        eq(reader.read().effectiveHead48(),24000,"coalesced first frame");
        eq(reader.read().effectiveHead48(),24000,"write growth without playback does not move cursor");
        eq(reader.read().effectiveHead48(),12000,"invalid higher track excluded");
        eq(reader.read().effectiveHead48(),-1,"all heads unavailable");
        for(int which=0;which<3;which++) {
            chain.reset();chain.write(bytes);out=new DataOutputStream(chain);
            VmicPolicy.ProgressWire.frame(out,which==0?1:2,
                    new long[]{48000,which==1?95999:96000},
                    new long[]{0,which==2?23999:24000},new int[]{0,1});
            reader=new VmicProgressWire.Reader(input(chain.toByteArray(),2));reader.read();
            try {reader.read();throw new AssertionError("accepted regressing sequence/counter "+which);}
            catch(IOException expected) {check(true,"regression rejected "+which);}
        }
        for(int tracks=1;tracks<=8;tracks++) {
            long[] w=new long[tracks],p=new long[tracks];int[] f=new int[tracks];
            for(int i=0;i<tracks;i++){w[i]=96000;p[i]=i*1000;f[i]=1;}
            VmicProgressWire.Frame frame=new VmicProgressWire.Reader(input(encode(tracks,w,p,f),3)).read();
            eq(frame.effectiveHead48(),tracks==1?-1:(tracks-1)*1000,"max valid head tracks="+tracks);
        }
    }
    private static void counters() {
        VmicPolicy.PlaybackCounter c=new VmicPolicy.PlaybackCounter();
        eq(c.sample(0,48000),0,"writes with zero head are not playback");
        eq(c.sample(24000,48000),24000,"real initial consumption");
        eq(c.sample(24000,96000),24000,"stall while writes grow");
        eq(c.sample(32000,96000),32000,"head resumes");
        eq(c.sample(40000,35000),35000,"head bounded by accepted frames");
        eq(c.sample(40000,50000),35000,"no catch-up from a write without head movement");
        eq(c.sample(41000,50000),41000,"real movement resumes after clamp");
        check(!c.invalid,"valid counter");
        eq(c.sample(0,50000),41000,"unexpected reset cannot invent a wrap");
        check(c.invalid,"counter invalid until new track");
        eq(c.sample(10000,60000),41000,"reset stays invalid");
        c=new VmicPolicy.PlaybackCounter();
        eq(c.sample(0x7fffffff,0x7fffffffL),0x7fffffffL,"before signed boundary");
        eq(c.sample(0x80000000,0x80000000L),0x80000000L,"uint32 signed flip");
        eq(c.sample(0xfffffffe,0xfffffffeL),0xfffffffeL,"before unsigned wrap");
        eq(c.sample(7,0x100000007L),0x100000007L,"uint32 cumulative wrap");
        eq(c.sample(7,0x100010000L),0x100000007L,"wrapped stall not derived from writes");
        check(!c.invalid,"wrap valid");
        eq(VmicProgressWire.toInput16(48000),16000,"48k to 16k");
        eq(VmicProgressWire.toInput16(48002),16000,"retain fractional samples across reports");
        eq(VmicProgressWire.toInput16(48003),16001,"no repeated rounding drift");
        eq(VmicProgressWire.toInput16(0x100000007L),0x100000007L/3,"long conversion after wrap");
        check(VmicProgressWire.isFresh(10,10),"fresh even monotonic epoch zero");
        check(VmicProgressWire.isFresh(10,1500000009L),"fresh before deadline");
        check(!VmicProgressWire.isFresh(10,1500000010L),"stale at deadline");
        check(!VmicProgressWire.isFresh(10,9),"negative elapsed rejected");
    }
    public static final class FakeTrack {
        final int limit;
        long written;
        int calls;
        FakeTrack(int limit) { this.limit=limit; }
        public int write(byte[] data,int off,int len,int mode) {
            check(mode==0,"existing blocking track mode preserved");
            if (++calls==1) return 0;
            int n=Math.min(limit,len);written+=n;return n;
        }
        public int getPlaybackHeadPosition() { return 0; }
    }
    private static void tracks() throws Exception {
        FakeTrack a=new FakeTrack(6),b=new FakeTrack(10);
        VmicPolicy.STOP=false;
        VmicPolicy.TRACKS.add(a);VmicPolicy.TRACKS.add(b);
        VmicPolicy.prepareWriter();
        VmicPolicy.writeAllTracks(new byte[34],0,34);
        eq(VmicPolicy.W_PROGRESS.length,2,"one progress state per writing track");
        eq(VmicPolicy.W_PROGRESS[0].acceptedBytes,34,"track zero short writes total");
        eq(VmicPolicy.W_PROGRESS[1].acceptedBytes,34,"track one independently accepted bytes");
        eq(VmicPolicy.W_PROGRESS[0].acceptedBytes/2,17,"per-track frames are not sum over tracks");
        eq(VmicPolicy.W_PROGRESS[0].counter.sample(12,17),12,"real head below partial write ceiling");
        eq(VmicPolicy.W_PROGRESS[1].counter.sample(0,17),0,"unused track does not mirror consuming track");
        check(VmicPolicy.W_PROGRESS[0].head!=null,"actual head accessor found");
        VmicPolicy.STOP=true;
        VmicPolicy.writeAllTracks(new byte[12],0,12);
        eq(VmicPolicy.W_PROGRESS[0].acceptedBytes,34,"stopped writer does not accept more");
        VmicPolicy.TRACKS.clear();VmicPolicy.W_TRACKS.clear();VmicPolicy.W_METHODS.clear();
        VmicPolicy.W_PROGRESS=new VmicPolicy.TrackProgress[0];
    }
    private static void pcm() throws Exception {
        byte[] pcm=new byte[11522];for(int i=0;i<pcm.length;i++)pcm[i]=(byte)(i*17);
        for(int fragment:new int[]{1,2,3,7,959,960,3839,3840,99999}) {
            VmicPolicy.PcmReader reader=new VmicPolicy.PcmReader(input(pcm,fragment));
            ByteArrayOutputStream got=new ByteArrayOutputStream();int n;
            while((n=reader.read())!=-1) {
                check((n&1)==0 && n>0,"PCM16 aligned at fragment "+fragment);
                got.write(reader.bytes,0,n);reader.consumed(n);
            }
            check(Arrays.equals(pcm,got.toByteArray()),"no loss/misalignment fragment "+fragment);
        }
        VmicPolicy.PcmReader reader=new VmicPolicy.PcmReader(input(new byte[]{1,2,3},1));
        int n=reader.read();eq(n,2,"odd tail first sample");reader.consumed(n);
        try{reader.read();throw new AssertionError("accepted odd EOF");}
        catch(EOFException expected){check(true,"incomplete PCM16 rejected");}
    }
    public static void main(String[] args) throws Exception {
        protocol();counters();tracks();pcm();
        System.out.println("PlaybackProgressTest checks="+checks+" PASS");
    }
}
