package io.freemytv.fairplay;

/** PlayFair's modified_md5.c (GPL-3.0): MD5's rounds with a word shuffle halfway through. */
final class ModifiedMd5 {
    private ModifiedMd5() {}

    private static final int[] SHIFT = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

    /** 64 bytes of block from off, the 16-byte key in, the 16-byte result out. Arithmetic is mod 2^32. */
    static void md5(byte[] block, int off, byte[] keyIn, byte[] keyOut) {
        byte[] b = new byte[64];
        System.arraycopy(block, off, b, 0, 64);
        int a = OmgHax.intLE(keyIn, 0), bb = OmgHax.intLE(keyIn, 4), c = OmgHax.intLE(keyIn, 8), d = OmgHax.intLE(keyIn, 12);
        for (int i = 0; i < 64; i++) {
            int j = i < 16 ? i : i < 32 ? (5 * i + 1) % 16 : i < 48 ? (3 * i + 5) % 16 : 7 * i % 16;
            int input = (b[4 * j] & 0xff) << 24 | (b[4 * j + 1] & 0xff) << 16 | (b[4 * j + 2] & 0xff) << 8 | (b[4 * j + 3] & 0xff);
            int z = a + input + (int) (long) ((1L << 32) * Math.abs(Math.sin(i + 1)));
            int f = i < 16 ? (bb & c) | (~bb & d) : i < 32 ? (bb & d) | (c & ~d) : i < 48 ? bb ^ c ^ d : c ^ (bb | ~d);
            z = Integer.rotateLeft(z + f, SHIFT[i]) + bb;
            int tmp = d;
            d = c;
            c = bb;
            bb = z;
            a = tmp;
            if (i == 31) {
                swap(b, 4 * (a & 15), 4 * (bb & 15));
                swap(b, 4 * (c & 15), 4 * (d & 15));
                swap(b, 4 * ((a >> 4) & 15), 4 * ((bb >> 4) & 15));
                swap(b, 4 * ((a >> 8) & 15), 4 * ((bb >> 8) & 15));
                swap(b, 4 * ((a >> 12) & 15), 4 * ((bb >> 12) & 15));
            }
        }
        OmgHax.putIntLE(keyOut, 0, OmgHax.intLE(keyIn, 0) + a);
        OmgHax.putIntLE(keyOut, 4, OmgHax.intLE(keyIn, 4) + bb);
        OmgHax.putIntLE(keyOut, 8, OmgHax.intLE(keyIn, 8) + c);
        OmgHax.putIntLE(keyOut, 12, OmgHax.intLE(keyIn, 12) + d);
    }

    private static void swap(byte[] b, int x, int y) {
        for (int k = 0; k < 4; k++) {
            byte t = b[x + k];
            b[x + k] = b[y + k];
            b[y + k] = t;
        }
    }
}
