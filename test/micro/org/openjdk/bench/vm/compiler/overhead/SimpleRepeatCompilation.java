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

package org.openjdk.bench.vm.compiler.overhead;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;
import java.util.Arrays;

/**
 * The purpose of these microbenchmarks is to use RepeatCompilation
 * to produce a benchmark that focuses on the overhead of various JIT
 * compilations themselves.
 */

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SimpleRepeatCompilation {

    public static final String METHOD = "org/openjdk/bench/vm/compiler/overhead/SimpleRepeatCompilation.mixHashCode"

    @Benchmark
    @Fork(value = 5, jvmArgsAppend={
        "-Xbatch",
        "-XX:CompileCommand=option," + METHOD + ",intx,RepeatCompilation,1000" })
    public int mixHashCode_repeat() {
        return loop_hashCode();
    }

    @Benchmark
    @Fork(value = 5, jvmArgsAppend={
        "-Xbatch",
        "-XX:-TieredCompilation",
        "-XX:CompileCommand=option," + METHOD + ",intx,RepeatCompilation,1000"
    })
    public int mixHashCode_repeat_c2() {
        return loop_hashCode();
    }

    @Benchmark
    @Fork(value = 5, jvmArgsAppend={
        "-Xbatch",
        "-XX:TieredStopAtLevel=1",
        "-XX:CompileCommand=option," + METHOD + ",intx,RepeatCompilation,1000"
    })
    public int mixHashCode_repeat_c1() {
        return loop_hashCode();
    }

    @Benchmark
    @Fork(value = 5, jvmArgsAppend={
        "-Xbatch" })
    public int mixHashCode_baseline() {
        return loop_hashCode();
    }

    public int loop_hashCode() {
        int value = 0;
        for (int i = 0; i < 1_000_000; i++) {
            mixHashCode("simple_string");
        }
        return value;
    }

    public int mixHashCode(String value) {
        int h = value.hashCode();
        for (int i = 0; i < value.length(); i++) {
            h = value.charAt(i) ^ h;
        }
        return h;
    }
}
