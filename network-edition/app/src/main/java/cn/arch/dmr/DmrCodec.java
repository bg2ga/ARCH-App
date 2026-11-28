package cn.arch.dmr;

import java.util.Arrays;

/** DMR 标准链路控制（LC）突发编码。 */
final class DmrCodec {
    private static final int[] INDEX_181 = {
        0,181,166,151,136,121,106,91,76,61,46,31,16,1,182,167,152,137,
        122,107,92,77,62,47,32,17,2,183,168,153,138,123,108,93,78,63,
        48,33,18,3,184,169,154,139,124,109,94,79,64,49,34,19,4,185,170,
        155,140,125,110,95,80,65,50,35,20,5,186,171,156,141,126,111,96,
        81,66,51,36,21,6,187,172,157,142,127,112,97,82,67,52,37,22,7,
        188,173,158,143,128,113,98,83,68,53,38,23,8,189,174,159,144,129,
        114,99,84,69,54,39,24,9,190,175,160,145,130,115,100,85,70,55,40,
        25,10,191,176,161,146,131,116,101,86,71,56,41,26,11,192,177,162,
        147,132,117,102,87,72,57,42,27,12,193,178,163,148,133,118,103,88,
        73,58,43,28,13,194,179,164,149,134,119,104,89,74,59,44,29,14,
        195,180,165,150,135,120,105,90,75,60,45,30,15
    };

    private static final int[] GOLAY = {
        0x0000,0xB08E,0xE093,0x501D,0x70A9,0xC027,0x903A,0x20B4,
        0x60DC,0xD052,0x804F,0x30C1,0x1075,0xA0FB,0xF0E6,0x4068,
        0x7036,0xC0B8,0x90A5,0x202B,0x009F,0xB011,0xE00C,0x5082,
        0x10EA,0xA064,0xF079,0x40F7,0x6043,0xD0CD,0x80D0,0x305E,
        0xD06C,0x60E2,0x30FF,0x8071,0xA0C5,0x104B,0x4056,0xF0D8,
        0xB0B0,0x003E,0x5023,0xE0AD,0xC019,0x7097,0x208A,0x9004,
        0xA05A,0x10D4,0x40C9,0xF047,0xD0F3,0x607D,0x3060,0x80EE,
        0xC086,0x7008,0x2015,0x909B,0xB02F,0x00A1,0x50BC,0xE032,
        0x90D9,0x2057,0x704A,0xC0C4,0xE070,0x50FE,0x00E3,0xB06D,
        0xF005,0x408B,0x1096,0xA018,0x80AC,0x3022,0x603F,0xD0B1,
        0xE0EF,0x5061,0x007C,0xB0F2,0x9046,0x20C8,0x70D5,0xC05B,
        0x8033,0x30BD,0x60A0,0xD02E,0xF09A,0x4014,0x1009,0xA087,
        0x40B5,0xF03B,0xA026,0x10A8,0x301C,0x8092,0xD08F,0x6001,
        0x2069,0x90E7,0xC0FA,0x7074,0x50C0,0xE04E,0xB053,0x00DD,
        0x3083,0x800D,0xD010,0x609E,0x402A,0xF0A4,0xA0B9,0x1037,
        0x505F,0xE0D1,0xB0CC,0x0042,0x20F6,0x9078,0xC065,0x70EB,
        0xA03D,0x10B3,0x40AE,0xF020,0xD094,0x601A,0x3007,0x8089,
        0xC0E1,0x706F,0x2072,0x90FC,0xB048,0x00C6,0x50DB,0xE055,
        0xD00B,0x6085,0x3098,0x8016,0xA0A2,0x102C,0x4031,0xF0BF,
        0xB0D7,0x0059,0x5044,0xE0CA,0xC07E,0x70F0,0x20ED,0x9063,
        0x7051,0xC0DF,0x90C2,0x204C,0x00F8,0xB076,0xE06B,0x50E5,
        0x108D,0xA003,0xF01E,0x4090,0x6024,0xD0AA,0x80B7,0x3039,
        0x0067,0xB0E9,0xE0F4,0x507A,0x70CE,0xC040,0x905D,0x20D3,
        0x60BB,0xD035,0x8028,0x30A6,0x1012,0xA09C,0xF081,0x400F,
        0x30E4,0x806A,0xD077,0x60F9,0x404D,0xF0C3,0xA0DE,0x1050,
        0x5038,0xE0B6,0xB0AB,0x0025,0x2091,0x901F,0xC002,0x708C,
        0x40D2,0xF05C,0xA041,0x10CF,0x307B,0x80F5,0xD0E8,0x6066,
        0x200E,0x9080,0xC09D,0x7013,0x50A7,0xE029,0xB034,0x00BA,
        0xE088,0x5006,0x001B,0xB095,0x9021,0x20AF,0x70B2,0xC03C,
        0x8054,0x30DA,0x60C7,0xD049,0xF0FD,0x4073,0x106E,0xA0E0,
        0x90BE,0x2030,0x702D,0xC0A3,0xE017,0x5099,0x0084,0xB00A,
        0xF062,0x40EC,0x10F1,0xA07F,0x80CB,0x3045,0x6058,0xD0D6
    };

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private DmrCodec() {}

    /** 生成一个 33 字节 DMRD 突发。term=false 为语音头，true 为结束帧。 */
    static byte[] lcBurst(int src, int dst, boolean term, int cc) {
        byte[] lc = new byte[9];
        putU24(lc, 3, dst);
        putU24(lc, 6, src);
        int[] lcBits = interleave(encode19696(appendRs(lc, term)));

        int dtype = term ? 2 : 1;
        int stByte = ((cc & 0x0F) << 4) | dtype;
        int golay = GOLAY[stByte];
        int st = (stByte << 12) | ((golay & 0xFF) << 4) | (golay >>> 12);

        int[] burst = new int[264];
        System.arraycopy(lcBits, 0, burst, 0, 98);
        for (int i = 0; i < 10; i++) burst[98 + i] = (st >>> (19 - i)) & 1;
        final byte[] sync = {(byte)0xDF, (byte)0xF5, (byte)0x7D, (byte)0x75, (byte)0xDF, (byte)0x5D};
        for (int i = 0; i < 48; i++) burst[108 + i] = (sync[i >> 3] >>> (7 - (i & 7))) & 1;
        for (int i = 0; i < 10; i++) burst[156 + i] = (st >>> (9 - i)) & 1;
        System.arraycopy(lcBits, 98, burst, 166, 98);

        byte[] out = new byte[33];
        for (int i = 0; i < 264; i++) if (burst[i] != 0) out[i >> 3] |= (byte)(1 << (7 - (i & 7)));
        return out;
    }

    private static byte[] appendRs(byte[] lc, boolean term) {
        byte[] m = new byte[12];
        System.arraycopy(lc, 0, m, 0, 9);
        int[] p = new int[3];
        for (byte b : lc) {
            int d = (b & 0xFF) ^ p[2];
            int p0 = p[0], p1 = p[1];
            p[0] = mul(64, d);
            p[1] = p0 ^ mul(56, d);
            p[2] = p1 ^ mul(14, d);
        }
        int mask = term ? 0x99 : 0x96;
        m[9] = (byte)(p[2] ^ mask);
        m[10] = (byte)(p[1] ^ mask);
        m[11] = (byte)(p[0] ^ mask);
        return m;
    }

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static int[] encode19696(byte[] twelve) {
        int[] a = new int[100];
        for (int i = 0; i < 96; i++) a[4 + i] = (twelve[i >> 3] >>> (7 - (i & 7))) & 1;
        for (int row = 0; row < 9; row++) {
            int spos = row * 15 + 1;
            int[] d = new int[11];
            System.arraycopy(a, spos, d, 0, 11);
            int[] p = enc15113(d);
            a = insert(a, spos + 11, p);
        }
        a = Arrays.copyOf(a, 196);
        for (int col = 0; col < 15; col++) {
            int[] d = new int[9];
            for (int i = 0; i < 9; i++) d[i] = a[col + 1 + i * 15];
            int[] p = enc1393(d);
            int cpar = 136 + col;
            for (int i = 0; i < 4; i++) { a[cpar] = p[i]; cpar += 15; }
        }
        return a;
    }

    private static int[] interleave(int[] data) {
        int[] out = new int[196];
        for (int i = 0; i < 196; i++) out[INDEX_181[i]] = data[i];
        return out;
    }

    private static int[] insert(int[] a, int pos, int[] bits) {
        int[] out = Arrays.copyOf(a, a.length + bits.length);
        System.arraycopy(a, pos, out, pos + bits.length, a.length - pos);
        System.arraycopy(bits, 0, out, pos, bits.length);
        return out;
    }

    private static int[] enc15113(int[] d) {
        return new int[]{
            d[0]^d[1]^d[2]^d[3]^d[5]^d[7]^d[8],
            d[1]^d[2]^d[3]^d[4]^d[6]^d[8]^d[9],
            d[2]^d[3]^d[4]^d[5]^d[7]^d[9]^d[10],
            d[0]^d[1]^d[2]^d[4]^d[6]^d[7]^d[10]
        };
    }

    private static int[] enc1393(int[] d) {
        return new int[]{
            d[0]^d[1]^d[3]^d[5]^d[6],
            d[0]^d[1]^d[2]^d[4]^d[6]^d[7],
            d[0]^d[1]^d[2]^d[3]^d[5]^d[7]^d[8],
            d[0]^d[2]^d[4]^d[5]^d[8]
        };
    }

    private static void putU24(byte[] out, int off, int v) {
        out[off] = (byte)(v >>> 16);
        out[off + 1] = (byte)(v >>> 8);
        out[off + 2] = (byte)v;
    }
}
