package io.freemytv.fairplay;

import static io.freemytv.fairplay.Tables.*;

/**
 * PlayFair's omg_hax.c (GPL-3.0), through java-airplay's Java port: the white-box AES-like cipher that unwraps the
 * stream key. Table lookups use offsets instead of copies, otherwise step for step the original.
 */
final class OmgHax {
    private OmgHax() {}

    /** The 16-byte key inside the 72-byte ekey, with the 164-byte /fp-setup message (message3). */
    static void decryptAesKey(byte[] message3, byte[] cipherText, byte[] keyOut) {
        byte[] blockIn = new byte[16];
        byte[] sapKey = new byte[16];
        int[][] keySchedule = new int[11][4];
        generateSessionKey(default_sap, message3, sapKey);
        generateKeySchedule(sapKey, keySchedule);
        for (int i = 0; i < 16; i++) blockIn[i] = (byte) (cipherText[56 + i] ^ z_key[i]);
        cycle(blockIn, keySchedule);
        for (int i = 0; i < 16; i++) keyOut[i] = (byte) (blockIn[i] ^ cipherText[16 + i] ^ x_key[i] ^ z_key[i]);
    }

    static void decryptMessage(byte[] messageIn, byte[] decryptedMessage) {
        byte[] buffer = new byte[16];
        byte tmp;
        int mode = messageIn[12];   // 0..3
        byte[] key = message_key[mode];
        for (int i = 0; i < 8; i++) {
            // The nth block (mode 3 starts from the last one).
            for (int j = 0; j < 16; j++) {
                buffer[j] = mode == 3 ? messageIn[(0x80 - 0x10 * i) + j] : messageIn[(0x10 * (i + 1)) + j];
            }
            for (int j = 0; j < 9; j++) {
                int base = 0x80 - 0x10 * j;
                buffer[0x0] = (byte) (m(base + 0x0, buffer[0x0]) ^ key[base + 0x0]);
                buffer[0x4] = (byte) (m(base + 0x4, buffer[0x4]) ^ key[base + 0x4]);
                buffer[0x8] = (byte) (m(base + 0x8, buffer[0x8]) ^ key[base + 0x8]);
                buffer[0xc] = (byte) (m(base + 0xc, buffer[0xc]) ^ key[base + 0xc]);

                tmp = buffer[0x0d];
                buffer[0xd] = (byte) (m(base + 0xd, buffer[0x9]) ^ key[base + 0xd]);
                buffer[0x9] = (byte) (m(base + 0x9, buffer[0x5]) ^ key[base + 0x9]);
                buffer[0x5] = (byte) (m(base + 0x5, buffer[0x1]) ^ key[base + 0x5]);
                buffer[0x1] = (byte) (m(base + 0x1, tmp) ^ key[base + 0x1]);

                tmp = buffer[0x02];
                buffer[0x2] = (byte) (m(base + 0x2, buffer[0xa]) ^ key[base + 0x2]);
                buffer[0xa] = (byte) (m(base + 0xa, tmp) ^ key[base + 0xa]);
                tmp = buffer[0x06];
                buffer[0x6] = (byte) (m(base + 0x6, buffer[0xe]) ^ key[base + 0x6]);
                buffer[0xe] = (byte) (m(base + 0xe, tmp) ^ key[base + 0xe]);

                tmp = buffer[0x3];
                buffer[0x3] = (byte) (m(base + 0x3, buffer[0x7]) ^ key[base + 0x3]);
                buffer[0x7] = (byte) (m(base + 0x7, buffer[0xb]) ^ key[base + 0x7]);
                buffer[0xb] = (byte) (m(base + 0xb, buffer[0xf]) ^ key[base + 0xb]);
                buffer[0xf] = (byte) (m(base + 0xf, tmp) ^ key[base + 0xf]);

                // Replace the buffer with 4 words, each the xor of 4 table words.
                for (int w = 0; w < 16; w += 4) {
                    putIntLE(buffer, w, table_s9[(buffer[w] & 0xff)] ^ table_s9[0x100 + (buffer[w + 1] & 0xff)]
                            ^ table_s9[0x200 + (buffer[w + 2] & 0xff)] ^ table_s9[0x300 + (buffer[w + 3] & 0xff)]);
                }
            }
            // Another permutation, with a different table.
            buffer[0x0] = table_s10[(0x0 << 8) + (buffer[0x0] & 0xff)];
            buffer[0x4] = table_s10[(0x4 << 8) + (buffer[0x4] & 0xff)];
            buffer[0x8] = table_s10[(0x8 << 8) + (buffer[0x8] & 0xff)];
            buffer[0xc] = table_s10[(0xc << 8) + (buffer[0xc] & 0xff)];

            tmp = buffer[0x0d];
            buffer[0xd] = table_s10[(0xd << 8) + (buffer[0x9] & 0xff)];
            buffer[0x9] = table_s10[(0x9 << 8) + (buffer[0x5] & 0xff)];
            buffer[0x5] = table_s10[(0x5 << 8) + (buffer[0x1] & 0xff)];
            buffer[0x1] = table_s10[(0x1 << 8) + (tmp & 0xff)];

            tmp = buffer[0x02];
            buffer[0x2] = table_s10[(0x2 << 8) + (buffer[0xa] & 0xff)];
            buffer[0xa] = table_s10[(0xa << 8) + (tmp & 0xff)];
            tmp = buffer[0x06];
            buffer[0x6] = table_s10[(0x6 << 8) + (buffer[0xe] & 0xff)];
            buffer[0xe] = table_s10[(0xe << 8) + (tmp & 0xff)];

            tmp = buffer[0x3];
            buffer[0x3] = table_s10[(0x3 << 8) + (buffer[0x7] & 0xff)];
            buffer[0x7] = table_s10[(0x7 << 8) + (buffer[0xb] & 0xff)];
            buffer[0xb] = table_s10[(0xb << 8) + (buffer[0xf] & 0xff)];
            buffer[0xf] = table_s10[(0xf << 8) + (tmp & 0xff)];

            // Xor with the previous block of the message (the IV for the first), in reverse for mode 3.
            if (mode != 3) {
                int at = 0x10 * i;
                for (int k = 0; k < 16; k++) {
                    decryptedMessage[at + k] = (byte) (buffer[k] ^ (i > 0 ? messageIn[at + k] : message_iv[mode][k]));
                }
            } else {
                int at = 0x70 - 0x10 * i;
                for (int k = 0; k < 16; k++) {
                    decryptedMessage[at + k] = (byte) (buffer[k] ^ (i < 7 ? messageIn[at + k] : message_iv[mode][k]));
                }
            }
        }
    }

    /** message_table_index(i)[b]: table_s2 at ((97 * i) % 144) << 8. */
    private static int m(int i, byte b) {
        return table_s2[(((97 * i) % 144) << 8) + (b & 0xff)];
    }

    static void generateKeySchedule(byte[] keyMaterial, int[][] keySchedule) {
        int[] keyData = new int[4];
        byte[] buffer = new byte[16];
        for (int i = 0; i < 16; i++) buffer[i] = (byte) (keyMaterial[i] ^ t_key[i]);
        for (int i = 0; i < 4; i++) keyData[i] = intLE(buffer, 4 * i);
        int ti = 0;
        for (int round = 0; round < 11; round++) {
            keySchedule[round][0] = keyData[0];
            buffer[0] ^= table_s1[s1(ti) + (buffer[0x0d] & 0xff)] ^ index_mangle[round];
            buffer[1] ^= table_s1[s1(ti + 1) + (buffer[0x0e] & 0xff)];
            buffer[2] ^= table_s1[s1(ti + 2) + (buffer[0x0f] & 0xff)];
            buffer[3] ^= table_s1[s1(ti + 3) + (buffer[0x0c] & 0xff)];
            ti += 4;
            keyData[0] = intLE(buffer, 0);
            keySchedule[round][1] = keyData[1];
            keyData[1] ^= keyData[0];
            putIntLE(buffer, 4, keyData[1]);
            keySchedule[round][2] = keyData[2];
            keyData[2] ^= keyData[1];
            putIntLE(buffer, 8, keyData[2]);
            keySchedule[round][3] = keyData[3];
            keyData[3] ^= keyData[2];
            putIntLE(buffer, 12, keyData[3]);
        }
    }

    /** table_index(i): table_s1 at ((31 * i) % 0x28) << 8. */
    private static int s1(int i) {
        return ((31 * i) % 0x28) << 8;
    }

    static void generateSessionKey(byte[] oldSap, byte[] messageIn, byte[] sessionKey) {
        byte[] decryptedMessage = new byte[128];
        byte[] newSap = new byte[320];
        byte[] md5 = new byte[16];
        decryptMessage(messageIn, decryptedMessage);
        System.arraycopy(static_source_1, 0, newSap, 0, 0x11);
        System.arraycopy(decryptedMessage, 0, newSap, 0x11, 0x80);
        System.arraycopy(oldSap, 0x80, newSap, 0x091, 0x80);
        System.arraycopy(static_source_2, 0, newSap, 0x111, 0x2f);
        System.arraycopy(initial_session_key, 0, sessionKey, 0, 16);
        for (int round = 0; round < 5; round++) {
            ModifiedMd5.md5(newSap, round * 64, sessionKey, md5);
            SapHash.hash(newSap, round * 64, sessionKey);
            for (int i = 0; i < 16; i += 4) putIntLE(sessionKey, i, intLE(sessionKey, i) + intLE(md5, i));
        }
        for (int i = 0; i < 16; i += 4) {
            byte tmp = sessionKey[i];
            sessionKey[i] = sessionKey[i + 3];
            sessionKey[i + 3] = tmp;
            tmp = sessionKey[i + 1];
            sessionKey[i + 1] = sessionKey[i + 2];
            sessionKey[i + 2] = tmp;
        }
        for (int i = 0; i < 16; i++) sessionKey[i] ^= 121;
    }

    static void cycle(byte[] block, int[][] keySchedule) {
        for (int w = 0; w < 4; w++) putIntLE(block, 4 * w, intLE(block, 4 * w) ^ keySchedule[10][w]);
        permuteBlock1(block);
        byte[] key = new byte[16];
        for (int round = 0; round < 9; round++) {
            for (int w = 0; w < 4; w++) putIntLE(key, 4 * w, keySchedule[9 - round][w]);
            for (int w = 0; w < 16; w += 4) {
                putIntLE(block, w, table_s5[(block[w + 3] & 0xff) ^ (key[w + 3] & 0xff)]
                        ^ table_s6[(block[w + 2] & 0xff) ^ (key[w + 2] & 0xff)]
                        ^ table_s7[(block[w + 1] & 0xff) ^ (key[w + 1] & 0xff)]
                        ^ table_s8[(block[w] & 0xff) ^ (key[w] & 0xff)]);
            }
            permuteBlock2(block, 8 - round);
        }
        for (int w = 0; w < 4; w++) putIntLE(block, 4 * w, intLE(block, 4 * w) ^ keySchedule[0][w]);
    }

    private static void permuteBlock1(byte[] block) {
        block[0] = table_s3[block[0] & 0xff];
        block[4] = table_s3[0x400 + (block[4] & 0xff)];
        block[8] = table_s3[0x800 + (block[8] & 0xff)];
        block[12] = table_s3[0xc00 + (block[12] & 0xff)];

        byte tmp = block[13];
        block[13] = table_s3[0x100 + (block[9] & 0xff)];
        block[9] = table_s3[0xd00 + (block[5] & 0xff)];
        block[5] = table_s3[0x900 + (block[1] & 0xff)];
        block[1] = table_s3[0x500 + (tmp & 0xff)];

        tmp = block[2];
        block[2] = table_s3[0xa00 + (block[10] & 0xff)];
        block[10] = table_s3[0x200 + (tmp & 0xff)];
        tmp = block[6];
        block[6] = table_s3[0xe00 + (block[14] & 0xff)];
        block[14] = table_s3[0x600 + (tmp & 0xff)];

        tmp = block[3];
        block[3] = table_s3[0xf00 + (block[7] & 0xff)];
        block[7] = table_s3[0x300 + (block[11] & 0xff)];
        block[11] = table_s3[0x700 + (block[15] & 0xff)];
        block[15] = table_s3[0xb00 + (tmp & 0xff)];
    }

    /** permute_table_2(i)[b]: table_s4 at ((71 * i) % 144) << 8. */
    private static byte p2(int i, byte b) {
        return table_s4[(((71 * i) % 144) << 8) + (b & 0xff)];
    }

    private static void permuteBlock2(byte[] block, int round) {
        int r = round * 16;
        block[0] = p2(r, block[0]);
        block[4] = p2(r + 4, block[4]);
        block[8] = p2(r + 8, block[8]);
        block[12] = p2(r + 12, block[12]);

        byte tmp = block[13];
        block[13] = p2(r + 13, block[9]);
        block[9] = p2(r + 9, block[5]);
        block[5] = p2(r + 5, block[1]);
        block[1] = p2(r + 1, tmp);

        tmp = block[2];
        block[2] = p2(r + 2, block[10]);
        block[10] = p2(r + 10, tmp);
        tmp = block[6];
        block[6] = p2(r + 6, block[14]);
        block[14] = p2(r + 14, tmp);

        tmp = block[3];
        block[3] = p2(r + 3, block[7]);
        block[7] = p2(r + 7, block[11]);
        block[11] = p2(r + 11, block[15]);
        block[15] = p2(r + 15, tmp);
    }

    static int intLE(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }
}
