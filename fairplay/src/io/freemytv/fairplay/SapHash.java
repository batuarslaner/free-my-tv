package io.freemytv.fairplay;

/** PlayFair's sap_hash.c (GPL-3.0), through java-airplay's Java port. Overwrites keyOut (its old value is unused). */
final class SapHash {
    private SapHash() {}

    private static byte rol8(byte input, int count) {
        return (byte) (((input << count) & 0xff) | (input & 0xff) >> (8 - count));
    }

    /** Hashes 64 bytes of block from off into the 16-byte keyOut. */
    static void hash(byte[] block, int off, byte[] keyOut) {
        byte[] buffer0 = {-106, 95, -58, 83, -8, 70, -52, 24, -33, -66, -78, -8, 56, -41, -20, 34, 3, -47, 32, -113};
        byte[] buffer1 = new byte[210];
        byte[] buffer2 = {67, 84, 98, 122, 24, -61, -42, -77, -102, 86, -10, 28, 20, 63, 12, 29, 59, 54, -125, -79, 57, 81,
                74, -86, 9, 62, -2, 68, -81, -34, -61, 32, -99, 66, 58};
        byte[] buffer3 = new byte[132];
        byte[] buffer4 = {-19, 37, -47, -69, -68, 39, -97, 2, -94, -87, 17, 0, 12, -77, 82, -64, -67, -29, 27, 73, -57};
        int[] i0Index = {18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30};
        byte w, x, y, z;

        // Load the input, swapping each word's byte order.
        for (int i = 0; i < 210; i++) {
            int word = OmgHax.intLE(block, off + ((i % 64) >> 2) * 4);
            buffer1[i] = (byte) ((word >> ((3 - (i % 4)) << 3)) & 0xff);
        }
        // Scramble (unsigned 32-bit modulo for the indices).
        for (int i = 0; i < 840; i++) {
            x = buffer1[(int) (((i - 155) & 0xffffffffL) % 210)];
            y = buffer1[(int) (((i - 57) & 0xffffffffL) % 210)];
            z = buffer1[(int) (((i - 13) & 0xffffffffL) % 210)];
            w = buffer1[(int) ((i & 0xffffffffL) % 210)];
            buffer1[i % 210] = (byte) ((rol8(y, 5) + (rol8(z, 3) ^ w) - rol8(x, 7)) & 0xff);
        }
        HandGarble.garble(buffer0, buffer1, buffer2, buffer3, buffer4);

        for (int i = 0; i < 16; i++) keyOut[i] = (byte) 0xE1;
        // buffer3 (addition, some indices; index 3 is always 0x3d), then buffer0, buffer2 and buffer1 xored in.
        for (int i = 0; i < 11; i++) {
            keyOut[i] = i == 3 ? 0x3d : (byte) ((keyOut[i] + buffer3[i0Index[i] * 4]) & 0xff);
        }
        for (int i = 0; i < 20; i++) keyOut[i % 16] ^= buffer0[i];
        for (int i = 0; i < 35; i++) keyOut[i % 16] ^= buffer2[i];
        for (int i = 0; i < 210; i++) keyOut[i % 16] ^= buffer1[i];
        // A reverse scramble.
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                x = keyOut[(int) (((i - 7) & 0xffffffffL) % 16)];
                y = keyOut[i % 16];
                z = keyOut[(int) (((i - 37) & 0xffffffffL) % 16)];
                w = keyOut[(int) (((i - 177) & 0xffffffffL) % 16)];
                keyOut[i] = (byte) (rol8(x, 1) ^ y ^ rol8(z, 6) ^ rol8(w, 5));
            }
        }
    }
}
