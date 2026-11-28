package cn.arch.dmr;

/** YSF DN-mode framing and AMBE+2 2450 codec, ported from DroidStar. */
public final class YsfCodec {
    private static final boolean LOADED;
    static { boolean ok=false; try { System.loadLibrary("archdstar_legacy"); ok=true; } catch(Throwable ignored) {} LOADED=ok; }
    private long h = LOADED ? nativeCreate() : 0;
    public boolean ok(){ return h != 0; }
    public void close(){ if(h!=0){ try{nativeDestroy(h);}catch(Throwable ignored){} h=0; } }
    public boolean encodeHeader(byte[] out, byte[] call10, int frameNo, boolean eot, int dgId){ return h!=0 && nativeEncodeHeader(h,out,call10,frameNo,eot,dgId); }
    public boolean encodeVoice(byte[] out, byte[] call10, short[] pcm800, int frameNo, int dgId){ return h!=0 && nativeEncodeVoice(h,out,call10,pcm800,frameNo,dgId); }
    public int frameType(byte[] in){ try{return nativeDecodeFrameType(in);}catch(Throwable ignored){return -1;} }
    public boolean decodeHeader(byte[] in, byte[] src10, byte[] dst10){ return nativeDecodeHeader(in,src10,dst10); }
    public boolean decodeVoice(byte[] in, short[] pcm800, byte[] src10, byte[] dst10){ return h!=0 && nativeDecodeVoice(h,in,pcm800,src10,dst10); }
    private static native long nativeCreate();
    private static native void nativeDestroy(long h);
    private static native boolean nativeEncodeHeader(long h,byte[] out,byte[] call,int frameNo,boolean eot,int dgId);
    private static native boolean nativeEncodeVoice(long h,byte[] out,byte[] call,short[] pcm,int frameNo,int dgId);
    private static native int nativeDecodeFrameType(byte[] in);
    private static native boolean nativeDecodeHeader(byte[] in,byte[] src,byte[] dst);
    private static native boolean nativeDecodeVoice(long h,byte[] in,short[] pcm,byte[] src,byte[] dst);
}
