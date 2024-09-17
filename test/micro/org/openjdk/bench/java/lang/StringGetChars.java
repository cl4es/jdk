/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.lang;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
@State(Scope.Thread)
public class StringGetChars {

    // Reduced by default to only UTF-8, previous coverage:
    // @Param({"US-ASCII", "ISO-8859-1", "UTF-8", "MS932", "ISO-8859-6", "ISO-2022-KR"})
    @Param({"UTF-8"})
    private String charsetName;

    private char[] chars;
    private String asciiString;
    private String longAsciiString;
    private String utf16String;
    private String longUtf16String;

    private static final String LOREM = """
             Lorem ipsum dolor sit amet, consectetur adipiscing elit. Aliquam ac sem eu
             urna egestas placerat. Etiam finibus ipsum nulla, non mattis dolor cursus a.
             Nulla nec nisl consectetur, lacinia neque id, accumsan ante. Curabitur et
             sapien in magna porta ultricies. Sed vel pellentesque nibh. Pellentesque dictum
             dignissim diam eu ultricies. Class aptent taciti sociosqu ad litora torquent
             per conubia nostra, per inceptos himenaeos. Suspendisse erat diam, fringilla
             sed massa sed, posuere viverra orci. Suspendisse tempor libero non gravida
             efficitur. Vivamus lacinia risus non orci viverra, at consectetur odio laoreet.
             Suspendisse potenti.""";
    private static final String UTF16_STRING = "\uFF11".repeat(31);

    @Setup
    public void setup() {
        asciiString = LOREM.substring(0, 32);
        longAsciiString = LOREM.repeat(200);
        utf16String = UTF16_STRING;
        longUtf16String = "\uFF11".repeat(LOREM.length() / 2).repeat(200);
        int size = Math.max(longAsciiString.length(), longUtf16String.length());
        chars = new char[size];
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public char[] getCharsAscii() throws Exception {
        asciiString.getChars(0, asciiString.length(), chars, 0);
        return chars;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public char[] getCharsAsciiLong() throws Exception {
        longAsciiString.getChars(0, longAsciiString.length(), chars, 0);
        return chars;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public char[] getCharsUTF16() throws Exception {
        utf16String.getChars(0, utf16String.length(), chars, 0);
        return chars;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public char[] getCharsUTF16Long() throws Exception {
        longUtf16String.getChars(0, longUtf16String.length(), chars, 0);
        return chars;
    }


}
