/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.bench.java.net;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Tests java.net.URLEncoder.encode and Decoder.decode.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 5)
@State(Scope.Thread)
public class URLEncodeDecode {

    @Param("512")
    public int count;

    @Param("128")
    public int maxLength;

    @Param("3")
    public long mySeed;

    public String[] testStringsEncodeNoneEncode;
    public String[] testStringsEncodeSomeEncode;
    public String[] testStringsEncodeMostEncode;
    public String[] testStringsDecode;
    public String[] toStrings;

    @Setup
    public void setupStrings() {
        StringBuilder sb = new StringBuilder();
        for (char c = '0'; c <= '9'; c++) {
            sb.append(c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            sb.append(c);
        }
        for (char c = 'a'; c <= 'z'; c++) {
            sb.append(c);
        }
        sb.append('-');
        sb.append('_');
        sb.append('.');
        sb.append('*');
        char[] tokens = sb.toString().toCharArray();

        Random r = new Random(mySeed);
        testStringsEncodeNoneEncode = new String[count];
        testStringsEncodeSomeEncode = new String[count];
        testStringsEncodeMostEncode = new String[count];
        testStringsDecode = new String[count];
        toStrings = new String[count];
        for (int i = 0; i < count; i++) {
            int l = r.nextInt(maxLength);

            // Strings requiring no encoding at all
            sb = new StringBuilder();
            for (int j = 0; j < l; j++) {
                int c = r.nextInt(tokens.length);
                sb.append(tokens[c]);
            }
            testStringsEncodeNoneEncode[i] = sb.toString();

            // Strings requiring some or no encoding
            for (int j = 0; j < l; j++) {
                int c = r.nextInt(100);
                if (c < 10) {
                    sb.replace(j, j + 1, String.valueOf(' '));
                }
                if (c > 95) {
                    sb.replace(j, j + 1, String.valueOf('\u0344'));
                }
            }
            testStringsEncodeSomeEncode[i] = sb.toString();

            // Mostly Strings requiring a lot of encoding. Mixed in with
            // some strings requiring some or no encoding.
            int c = r.nextInt(100);
            if (c >= 98) {
                testStringsEncodeMostEncode[i] = testStringsEncodeNoneEncode[i];
            } else if (c <= 5) {
                testStringsEncodeMostEncode[i] = testStringsEncodeSomeEncode[i];
            } else {
                // Generate completely random String
                sb = new StringBuilder();
                for (int j = 0; j < l; j++) {
                    sb.append((char) r.nextInt(20000));
                }
                testStringsEncodeMostEncode[i] = sb.toString();
            }
        }

        for (int i = 0; i < count; i++) {
            int l = r.nextInt(maxLength);
            sb = new StringBuilder();
            for (int j = 0; j < l; j++) {
                int c = r.nextInt(tokens.length + 5);
                if (c >= tokens.length) {
                    sb.append("%").append(tokens[r.nextInt(16)]).append(tokens[r.nextInt(16)]);
                } else {
                    sb.append(tokens[c]);
                }
            }
            testStringsDecode[i] = sb.toString();
        }
    }

    @Benchmark
    public void testEncodeUTF8NoEncode(Blackhole bh) throws UnsupportedEncodingException {
        for (String s : testStringsEncodeNoneEncode) {
            bh.consume(java.net.URLEncoder.encode(s, "UTF-8"));
        }
    }

    @Benchmark
    public void testEncodeUTF8SomeEncode(Blackhole bh) throws UnsupportedEncodingException {
        for (String s : testStringsEncodeSomeEncode) {
            bh.consume(java.net.URLEncoder.encode(s, "UTF-8"));
        }
    }

    @Benchmark
    public void testEncodeUTF8MostEncode(Blackhole bh) throws UnsupportedEncodingException {
        for (String s : testStringsEncodeMostEncode) {
            bh.consume(java.net.URLEncoder.encode(s, "UTF-8"));
        }
    }

    @Benchmark
    public void testDecodeUTF8(Blackhole bh) throws UnsupportedEncodingException {
        for (String s : testStringsDecode) {
            bh.consume(URLDecoder.decode(s, "UTF-8"));
        }
    }


}
