package cn.arch.dmr;

/** FMO RADPCM (IMA ADPCM) 编解码，移植自官方开放协议参考实现。 */
final class FmoRadPcm {
    private static final int[] STEP = {
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,
        157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,
        1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,
        10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767
    };
    private static final int[] INDEX = {-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8};
    private static final int R = 32735;
    private int predictor, index, prevX, prevY, frameIndex;

    synchronized void reset() { predictor=index=prevX=prevY=frameIndex=0; }

    synchronized short[] decode(byte[] frame) {
        short[] out = new short[640];
        if (frame == null || frame.length < 8) return out;
        int fi = u16(frame,0), recov = (short)u16(frame,2), step = frame[4]&255;
        int dataLen = frame.length - 8;
        if (dataLen <= 0 || dataLen > 320) {
            dataLen = frame.length > 6 ? (frame[6]&255) : 320;
            if (dataLen <= 0 || dataLen > 320) dataLen = 320;
        }
        if (fi == 0 || fi != (frameIndex & 0xffff)) { predictor=recov; index=step; }
        int p=0;
        for (int bi=8; bi<8+dataLen && p<out.length; bi++) {
            int b=frame[bi]&255;
            for (int half=0; half<2 && p<out.length; half++) {
                int code = half == 0 ? ((b >>> 4) & 15) : (b & 15);
                int st=STEP[index], delta=st>>>3;
                if ((code&4)!=0) delta+=st;
                if ((code&2)!=0) delta+=st>>>1;
                if ((code&1)!=0) delta+=st>>>2;
                if ((code&8)!=0) predictor-=delta; else predictor+=delta;
                if (predictor>32767) predictor=32767; if (predictor<-32768) predictor=-32768;
                index += INDEX[code]; if(index<0)index=0; if(index>88)index=88;
                out[p++]=(short)predictor;
            }
        }
        frameIndex=(fi+1)&0xffff;
        return out;
    }

    synchronized byte[] encode(short[] samples) {
        if (samples.length < 640) return new byte[328];
        int recov=predictor, recovIdx=index;
        byte[] data=new byte[320];
        for(int i=0,p=0;i<640;i+=2){
            int hi=enc(samples[i]); int lo=enc(samples[i+1]); data[p++]=(byte)((hi<<4)|lo);
        }
        byte[] out=new byte[328]; put16(out,0,frameIndex); put16(out,2,recov); out[4]=(byte)recovIdx; out[6]=(byte)0x40; put16(out,6,320); System.arraycopy(data,0,out,8,320);
        frameIndex=(frameIndex+1)&0xffff; return out;
    }
    private int enc(int x) {
        int y=x-prevX+((R*prevY)>>15); if(y>32767)y=32767; if(y<-32768)y=-32768; prevX=x; prevY=y;
        int st=STEP[index], diff=y-predictor, code=0; if(diff<0){code=8;diff=-diff;}
        int delta=st>>>3; if(diff>=st){code|=4;diff-=st;delta+=st;} st>>>=1; if(diff>=st){code|=2;diff-=st;delta+=st;} st>>>=1; if(diff>=st){code|=1;delta+=st;}
        if((code&8)!=0)predictor-=delta;else predictor+=delta; if(predictor>32767)predictor=32767; if(predictor<-32768)predictor=-32768;
        index+=INDEX[code]; if(index<0)index=0; if(index>88)index=88; return code;
    }
    private static int u16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    private static void put16(byte[] b,int p,int v){b[p]=(byte)v;b[p+1]=(byte)(v>>>8);}
}
