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
package org.openjdk.bench.vm.compiler;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.*;

import java.util.concurrent.TimeUnit;
import java.util.Random;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public abstract class VectorIdiom {
    @Param({//"2", // "4"
        // "8",
        "16",
        // "32",
        // "64"
        // "128"
        })
    public int COUNT;

    private static int[] intsA;
    private static byte[] bytesA;
    private static float[] floatsA;
    private static double[] doublesA;
    private static double[] doublesB;
    private static float[][] A;
    private static float[] b;
    private static Random r = new Random();

    float[][] randomDDMatrix(int n) {
        float MIN_ELEM = 1;
        float MAX_ELEM = 100;

        float[][] m = new float[n][n];
        for (int i = 0; i < n; i++) {
            float rowSum = 0;
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    float elem = MIN_ELEM + r.nextFloat()*(MAX_ELEM - MIN_ELEM);
                    m[i][j] = elem;
                    rowSum += elem;
                }
            }
            m[i][i] = rowSum + 1;
        }

        return m;
    }

    float[] randomVector(int n) {
        float MIN_ELEM = 1;
        float MAX_ELEM = 100;

        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = MIN_ELEM + r.nextFloat()*(MAX_ELEM - MIN_ELEM);
        }

        return v;
    }

    @Setup
    public void init() {
        intsA = new int[COUNT];
        bytesA = new byte[COUNT];
        r.nextBytes(bytesA);

        floatsA = new float[COUNT];
        doublesA = new double[COUNT];
        doublesB = new double[COUNT];
        for (int i = 0; i < COUNT; i++) {
            floatsA[i] = r.nextFloat();
            doublesA[i] = r.nextDouble();
            doublesB[i] = r.nextDouble();
        }

        A = randomDDMatrix(COUNT);
        b = randomVector(COUNT);
    }

    // @Benchmark
    // public float[] jacobiWrap() {
    //     return jacobi(A, b, 1);
    // }

    @Benchmark
    public int stringHashCodeWrap() {
        return stringHashCodea(bytesA);
    }

    public int stringHashCodea(byte[] a) {
        int h = 0;
        for (int i = 0; i < a.length; i++) {
          h = 31*h + (a[i] & 0xFF);
        }
        return h;
    }

    @Benchmark
    public double dotWrap() {
        return dot(doublesA, doublesB);
    }

    public double dot(double[] a, double[] b) {
        double dotProduct = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i]*b[i];
        }
        return dotProduct;
    }

    // @Benchmark
    // public float floatMulReductionWrap() {
    //     return floatReduction(floatsA);
    // }

    public float floatMulReduction(float[] a) {
        float h = 0;
        for (float v : a) {
            h = 2*h + v;
        }
        return h;
    }

    // @Benchmark
    // public float floatIdentityReductionWrap() {
    //     return floatIdentityReduction(floatsA);
    // }

    public float floatIdentityReduction(float[] a) {
        float h = 0;
        for (float v : a) {
            h += v;
        }
        return h;
    }

    // @Benchmark
    // public float floatMatrixSplitLoopWrap() {
    //     return floatMatrixSplitLoop(A);
    // }

    // public float floatMatrixSplitLoop(float[][] a) {
    //     float h = 0;
    //     for (int i = 0; i < a.length; i++) {
    //         float[] row = a[i];
    //         for (int j = 0; j < i; j++) {
    //             h = h + (row[j] + 2*row[j]);
    //         }
    //         for (int j = i+1; j < row.length; j++) {
    //             h = h + (row[j] + 2*row[j]);
    //         }
    //     }
    //     return h;
    // }

    // @Fork(value = 1, jvmArgsPrepend = {
    //         "-XX:+IdiomVectorize",
    //         "-XX:IdiomVectorWidth=16",
    //         "-XX:+IdiomVectorizeAlign"
    //     })
    // public static class VectorXMMAligned extends VectorIdiom { }

    // @Fork(value = 1, jvmArgsPrepend = {
    //   "-XX:+IdiomVectorize",
    //   "-XX:IdiomVectorWidth=32",
    //   "-XX:+IdiomVectorizeAlign"
    // })
    // public static class VectorYMMAligned extends VectorIdiom { }

   @Fork(value = 1, jvmArgsPrepend = {
            "-XX:+IdiomVectorize",
            "-XX:IdiomVectorWidth=16",
            // "-XX:+UnlockDiagnosticVMOptions",
            // "-XX:+PrintOptoAssembly"
        })
    public static class VectorXMM extends VectorIdiom { }

    // @Fork(value = 1, jvmArgsPrepend = {
    //   "-XX:+IdiomVectorize",
    //   "-XX:IdiomVectorWidth=32"
    // })
    // public static class VectorYMM extends VectorIdiom { }

    @Fork(value = 1, jvmArgsPrepend = {
        "-XX:-IdiomVectorize",
        // "-XX:+UnlockDiagnosticVMOptions",
        // "-XX:+PrintOptoAssembly"
      })
    public static class VectorXNone extends VectorIdiom { }
}
