/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.io;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Tests the overheads of creating Paths from a File.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(time=2, iterations=5)
@Measurement(time=3, iterations=5)
@Fork(value=2, jvmArgs="-Xmx1g")
public class FileToPath {

    public File normalFile;
    public File trailingSlash;
    public File notNormalizedFile = "/test/dir/file//name.txt";


    @Setup
    public void setup() throws IOException {
        normalFile = new File("/test/dir/file/name.txt");
        trailingSlash = new File("/test/dir/file/name.txt/");
        notNormalizedFile = new File("/test/dir/file//name.txt");
    }

    @Benchmark
    public void mix(Blackhole bh) {
        bh.consume(normalFile.toPath());
        bh.consume(trailingSlash.toPath());
        bh.consume(notNormalizedFile.toPath());
    }

    @Benchmark
    public Path normalized() {
        return normalFile.toPath();
    }

    @Benchmark
    public Path trailingSlash() {
        return trailingSlash.toPath();
    }

    @Benchmark
    public Path notNormalized() {
        return notNormalizedFile.toPath();
    }

}
