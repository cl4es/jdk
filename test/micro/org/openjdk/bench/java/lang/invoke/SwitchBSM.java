/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.lang.invoke;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class SwitchBSM {

    private Object[] data40;
    private Object[] data100;
    private MethodHandle handleMethodHandleCascade40;
    private MethodHandle handleIfElseCascade40;
    private MethodHandle handleConditionalCascade40;
    private MethodHandle handleMethodHandleCascade100;
    private MethodHandle handleIfElseCascade100;
    private MethodHandle handleConditionalCascade100;
    private MethodHandle handleLoop100;
    private MethodHandle handleLoopList100;

    //    @Benchmark
    public void testMethodHandles100(Blackhole b) throws Throwable {
        for (Object v : data100) {
            b.consume((int) handleMethodHandleCascade100.invokeExact(v));
        }
    }

    @Benchmark
    public void testLoop100(Blackhole b) throws Throwable {
        for (Object v : data100) {
            b.consume((int) handleLoop100.invokeExact(v));
        }
    }

    @Benchmark
    public void testLoopList100(Blackhole b) throws Throwable {
        for (Object v : data100) {
            b.consume((int) handleLoopList100.invokeExact(v));
        }
    }

    private static int categorizeByLoop(Object v, Class<?>[] labels) {
        int len = labels.length;
        for (int i = 0; i < labels.length; i++) {
            Class<?> label = labels[i];
            if (label.isInstance(v)) {
                return i;
            }
        }
        return len;
    }

    private static int categorizeByLoopList(Object v, List<Class<?>> labels) {
        int len = labels.size();
        for (int i = 0; i < len; i++) {
            Class<?> label = labels.get(i);
            if (label.isInstance(v)) {
                return i;
            }
        }
        return len;
    }

    //    @Benchmark
    public void testIfElse100(Blackhole b) throws Throwable {
        for (Object v : data100) {
            b.consume((int) handleIfElseCascade100.invokeExact(v));
        }
    }

    @Benchmark
    public void testConditional100(Blackhole b) throws Throwable {
        for (Object v : data100) {
            b.consume((int) handleConditionalCascade100.invokeExact(v));
        }
    }

    //    @Benchmark
    public void testMethodHandles40(Blackhole b) throws Throwable {
        for (Object v : data40) {
            b.consume((int) handleMethodHandleCascade40.invokeExact(v));
        }
    }

    //    @Benchmark
    public void testIfElse40(Blackhole b) throws Throwable {
        for (Object v : data100) {
            b.consume((int) handleIfElseCascade40.invokeExact(v));
        }
    }

    //    @Benchmark
    public void testConditional40(Blackhole b) throws Throwable {
        for (Object v : data40) {
            b.consume((int) handleConditionalCascade40.invokeExact(v));
        }
    }

    private static int doIfCascade100(Object value) {
        if (value instanceof Test.R00) {
            return 0;
        } else if (value instanceof Test.R01) {
            return 1;
        } else if (value instanceof Test.R02) {
            return 2;
        } else if (value instanceof Test.R03) {
            return 3;
        } else if (value instanceof Test.R04) {
            return 4;
        } else if (value instanceof Test.R05) {
            return 5;
        } else if (value instanceof Test.R06) {
            return 6;
        } else if (value instanceof Test.R07) {
            return 7;
        } else if (value instanceof Test.R08) {
            return 8;
        } else if (value instanceof Test.R09) {
            return 9;
        } else if (value instanceof Test.R10) {
            return 10;
        } else if (value instanceof Test.R11) {
            return 11;
        } else if (value instanceof Test.R12) {
            return 12;
        } else if (value instanceof Test.R13) {
            return 13;
        } else if (value instanceof Test.R14) {
            return 14;
        } else if (value instanceof Test.R15) {
            return 15;
        } else if (value instanceof Test.R16) {
            return 16;
        } else if (value instanceof Test.R17) {
            return 17;
        } else if (value instanceof Test.R18) {
            return 18;
        } else if (value instanceof Test.R19) {
            return 19;
        } else if (value instanceof Test.R20) {
            return 20;
        } else if (value instanceof Test.R21) {
            return 21;
        } else if (value instanceof Test.R22) {
            return 22;
        } else if (value instanceof Test.R23) {
            return 23;
        } else if (value instanceof Test.R24) {
            return 24;
        } else if (value instanceof Test.R25) {
            return 25;
        } else if (value instanceof Test.R26) {
            return 26;
        } else if (value instanceof Test.R27) {
            return 27;
        } else if (value instanceof Test.R28) {
            return 28;
        } else if (value instanceof Test.R29) {
            return 29;
        } else if (value instanceof Test.R30) {
            return 30;
        } else if (value instanceof Test.R31) {
            return 31;
        } else if (value instanceof Test.R32) {
            return 32;
        } else if (value instanceof Test.R33) {
            return 33;
        } else if (value instanceof Test.R34) {
            return 34;
        } else if (value instanceof Test.R35) {
            return 35;
        } else if (value instanceof Test.R36) {
            return 36;
        } else if (value instanceof Test.R37) {
            return 37;
        } else if (value instanceof Test.R38) {
            return 38;
        } else if (value instanceof Test.R39) {
            return 39;
        } else if (value instanceof Test.R40) {
            return 40;
        } else if (value instanceof Test.R41) {
            return 41;
        } else if (value instanceof Test.R42) {
            return 42;
        } else if (value instanceof Test.R43) {
            return 43;
        } else if (value instanceof Test.R44) {
            return 44;
        } else if (value instanceof Test.R45) {
            return 45;
        } else if (value instanceof Test.R46) {
            return 46;
        } else if (value instanceof Test.R47) {
            return 47;
        } else if (value instanceof Test.R48) {
            return 48;
        } else if (value instanceof Test.R49) {
            return 49;
        } else if (value instanceof Test.R50) {
            return 50;
        } else if (value instanceof Test.R51) {
            return 51;
        } else if (value instanceof Test.R52) {
            return 52;
        } else if (value instanceof Test.R53) {
            return 53;
        } else if (value instanceof Test.R54) {
            return 54;
        } else if (value instanceof Test.R55) {
            return 55;
        } else if (value instanceof Test.R56) {
            return 56;
        } else if (value instanceof Test.R57) {
            return 57;
        } else if (value instanceof Test.R58) {
            return 58;
        } else if (value instanceof Test.R59) {
            return 59;
        } else if (value instanceof Test.R60) {
            return 60;
        } else if (value instanceof Test.R61) {
            return 61;
        } else if (value instanceof Test.R62) {
            return 62;
        } else if (value instanceof Test.R63) {
            return 63;
        } else if (value instanceof Test.R64) {
            return 64;
        } else if (value instanceof Test.R65) {
            return 65;
        } else if (value instanceof Test.R66) {
            return 66;
        } else if (value instanceof Test.R67) {
            return 67;
        } else if (value instanceof Test.R68) {
            return 68;
        } else if (value instanceof Test.R69) {
            return 69;
        } else if (value instanceof Test.R70) {
            return 70;
        } else if (value instanceof Test.R71) {
            return 71;
        } else if (value instanceof Test.R72) {
            return 72;
        } else if (value instanceof Test.R73) {
            return 73;
        } else if (value instanceof Test.R74) {
            return 74;
        } else if (value instanceof Test.R75) {
            return 75;
        } else if (value instanceof Test.R76) {
            return 76;
        } else if (value instanceof Test.R77) {
            return 77;
        } else if (value instanceof Test.R78) {
            return 78;
        } else if (value instanceof Test.R79) {
            return 79;
        } else if (value instanceof Test.R80) {
            return 80;
        } else if (value instanceof Test.R81) {
            return 81;
        } else if (value instanceof Test.R82) {
            return 82;
        } else if (value instanceof Test.R83) {
            return 83;
        } else if (value instanceof Test.R84) {
            return 84;
        } else if (value instanceof Test.R85) {
            return 85;
        } else if (value instanceof Test.R86) {
            return 86;
        } else if (value instanceof Test.R87) {
            return 87;
        } else if (value instanceof Test.R88) {
            return 88;
        } else if (value instanceof Test.R89) {
            return 89;
        } else if (value instanceof Test.R90) {
            return 90;
        } else if (value instanceof Test.R91) {
            return 91;
        } else if (value instanceof Test.R92) {
            return 92;
        } else if (value instanceof Test.R93) {
            return 93;
        } else if (value instanceof Test.R94) {
            return 94;
        } else if (value instanceof Test.R95) {
            return 95;
        } else if (value instanceof Test.R96) {
            return 96;
        } else if (value instanceof Test.R97) {
            return 97;
        } else if (value instanceof Test.R98) {
            return 98;
        } else if (value instanceof Test.R99) {
            return 99;
        } else {
            return 100;
        }
    }

    private static int doConditionalCascade100(Object value) {
        return value instanceof Test.R00 ? 0
                : value instanceof Test.R01 ? 1
                : value instanceof Test.R02 ? 2
                : value instanceof Test.R03 ? 3
                : value instanceof Test.R04 ? 4
                : value instanceof Test.R05 ? 5
                : value instanceof Test.R06 ? 6
                : value instanceof Test.R07 ? 7
                : value instanceof Test.R08 ? 8
                : value instanceof Test.R09 ? 9
                : value instanceof Test.R10 ? 10
                : value instanceof Test.R11 ? 11
                : value instanceof Test.R12 ? 12
                : value instanceof Test.R13 ? 13
                : value instanceof Test.R14 ? 14
                : value instanceof Test.R15 ? 15
                : value instanceof Test.R16 ? 16
                : value instanceof Test.R17 ? 17
                : value instanceof Test.R18 ? 18
                : value instanceof Test.R19 ? 19
                : value instanceof Test.R20 ? 20
                : value instanceof Test.R21 ? 21
                : value instanceof Test.R22 ? 22
                : value instanceof Test.R23 ? 23
                : value instanceof Test.R24 ? 24
                : value instanceof Test.R25 ? 25
                : value instanceof Test.R26 ? 26
                : value instanceof Test.R27 ? 27
                : value instanceof Test.R28 ? 28
                : value instanceof Test.R29 ? 29
                : value instanceof Test.R30 ? 30
                : value instanceof Test.R31 ? 31
                : value instanceof Test.R32 ? 32
                : value instanceof Test.R33 ? 33
                : value instanceof Test.R34 ? 34
                : value instanceof Test.R35 ? 35
                : value instanceof Test.R36 ? 36
                : value instanceof Test.R37 ? 37
                : value instanceof Test.R38 ? 38
                : value instanceof Test.R39 ? 39
                : value instanceof Test.R40 ? 40
                : value instanceof Test.R41 ? 41
                : value instanceof Test.R42 ? 42
                : value instanceof Test.R43 ? 43
                : value instanceof Test.R44 ? 44
                : value instanceof Test.R45 ? 45
                : value instanceof Test.R46 ? 46
                : value instanceof Test.R47 ? 47
                : value instanceof Test.R48 ? 48
                : value instanceof Test.R49 ? 49
                : value instanceof Test.R50 ? 50
                : value instanceof Test.R51 ? 51
                : value instanceof Test.R52 ? 52
                : value instanceof Test.R53 ? 53
                : value instanceof Test.R54 ? 54
                : value instanceof Test.R55 ? 55
                : value instanceof Test.R56 ? 56
                : value instanceof Test.R57 ? 57
                : value instanceof Test.R58 ? 58
                : value instanceof Test.R59 ? 59
                : value instanceof Test.R60 ? 60
                : value instanceof Test.R61 ? 61
                : value instanceof Test.R62 ? 62
                : value instanceof Test.R63 ? 63
                : value instanceof Test.R64 ? 64
                : value instanceof Test.R65 ? 65
                : value instanceof Test.R66 ? 66
                : value instanceof Test.R67 ? 67
                : value instanceof Test.R68 ? 68
                : value instanceof Test.R69 ? 69
                : value instanceof Test.R70 ? 70
                : value instanceof Test.R71 ? 71
                : value instanceof Test.R72 ? 72
                : value instanceof Test.R73 ? 73
                : value instanceof Test.R74 ? 74
                : value instanceof Test.R75 ? 75
                : value instanceof Test.R76 ? 76
                : value instanceof Test.R77 ? 77
                : value instanceof Test.R78 ? 78
                : value instanceof Test.R79 ? 79
                : value instanceof Test.R80 ? 80
                : value instanceof Test.R81 ? 81
                : value instanceof Test.R82 ? 82
                : value instanceof Test.R83 ? 83
                : value instanceof Test.R84 ? 84
                : value instanceof Test.R85 ? 85
                : value instanceof Test.R86 ? 86
                : value instanceof Test.R87 ? 87
                : value instanceof Test.R88 ? 88
                : value instanceof Test.R89 ? 89
                : value instanceof Test.R90 ? 90
                : value instanceof Test.R91 ? 91
                : value instanceof Test.R92 ? 92
                : value instanceof Test.R93 ? 93
                : value instanceof Test.R94 ? 94
                : value instanceof Test.R95 ? 95
                : value instanceof Test.R96 ? 96
                : value instanceof Test.R97 ? 97
                : value instanceof Test.R98 ? 98
                : value instanceof Test.R99 ? 99
                : 100;
    }

    private static int doIfCascade40(Object value) {
        if (value instanceof Test.R00) {
            return 0;
        } else if (value instanceof Test.R01) {
            return 1;
        } else if (value instanceof Test.R02) {
            return 2;
        } else if (value instanceof Test.R03) {
            return 3;
        } else if (value instanceof Test.R04) {
            return 4;
        } else if (value instanceof Test.R05) {
            return 5;
        } else if (value instanceof Test.R06) {
            return 6;
        } else if (value instanceof Test.R07) {
            return 7;
        } else if (value instanceof Test.R08) {
            return 8;
        } else if (value instanceof Test.R09) {
            return 9;
        } else if (value instanceof Test.R10) {
            return 10;
        } else if (value instanceof Test.R11) {
            return 11;
        } else if (value instanceof Test.R12) {
            return 12;
        } else if (value instanceof Test.R13) {
            return 13;
        } else if (value instanceof Test.R14) {
            return 14;
        } else if (value instanceof Test.R15) {
            return 15;
        } else if (value instanceof Test.R16) {
            return 16;
        } else if (value instanceof Test.R17) {
            return 17;
        } else if (value instanceof Test.R18) {
            return 18;
        } else if (value instanceof Test.R19) {
            return 19;
        } else if (value instanceof Test.R20) {
            return 20;
        } else if (value instanceof Test.R21) {
            return 21;
        } else if (value instanceof Test.R22) {
            return 22;
        } else if (value instanceof Test.R23) {
            return 23;
        } else if (value instanceof Test.R24) {
            return 24;
        } else if (value instanceof Test.R25) {
            return 25;
        } else if (value instanceof Test.R26) {
            return 26;
        } else if (value instanceof Test.R27) {
            return 27;
        } else if (value instanceof Test.R28) {
            return 28;
        } else if (value instanceof Test.R29) {
            return 29;
        } else if (value instanceof Test.R30) {
            return 30;
        } else if (value instanceof Test.R31) {
            return 31;
        } else if (value instanceof Test.R32) {
            return 32;
        } else if (value instanceof Test.R33) {
            return 33;
        } else if (value instanceof Test.R34) {
            return 34;
        } else if (value instanceof Test.R35) {
            return 35;
        } else if (value instanceof Test.R36) {
            return 36;
        } else if (value instanceof Test.R37) {
            return 37;
        } else if (value instanceof Test.R38) {
            return 38;
        } else if (value instanceof Test.R39) {
            return 39;
        } else {
            return 100;
        }
    }

    private static int doConditionalCascade40(Object value) {
        return value instanceof Test.R00 ? 0
                : value instanceof Test.R01 ? 1
                : value instanceof Test.R02 ? 2
                : value instanceof Test.R03 ? 3
                : value instanceof Test.R04 ? 4
                : value instanceof Test.R05 ? 5
                : value instanceof Test.R06 ? 6
                : value instanceof Test.R07 ? 7
                : value instanceof Test.R08 ? 8
                : value instanceof Test.R09 ? 9
                : value instanceof Test.R10 ? 10
                : value instanceof Test.R11 ? 11
                : value instanceof Test.R12 ? 12
                : value instanceof Test.R13 ? 13
                : value instanceof Test.R14 ? 14
                : value instanceof Test.R15 ? 15
                : value instanceof Test.R16 ? 16
                : value instanceof Test.R17 ? 17
                : value instanceof Test.R18 ? 18
                : value instanceof Test.R19 ? 19
                : value instanceof Test.R20 ? 20
                : value instanceof Test.R21 ? 21
                : value instanceof Test.R22 ? 22
                : value instanceof Test.R23 ? 23
                : value instanceof Test.R24 ? 24
                : value instanceof Test.R25 ? 25
                : value instanceof Test.R26 ? 26
                : value instanceof Test.R27 ? 27
                : value instanceof Test.R28 ? 28
                : value instanceof Test.R29 ? 29
                : value instanceof Test.R30 ? 30
                : value instanceof Test.R31 ? 31
                : value instanceof Test.R32 ? 32
                : value instanceof Test.R33 ? 33
                : value instanceof Test.R34 ? 34
                : value instanceof Test.R35 ? 35
                : value instanceof Test.R36 ? 36
                : value instanceof Test.R37 ? 37
                : value instanceof Test.R38 ? 38
                : value instanceof Test.R39 ? 39
                : 100;
    }

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle INSTANCEOF_CHECK;

    static {
        try {
            INSTANCEOF_CHECK = MethodHandles.permuteArguments(LOOKUP.findVirtual(Class.class, "isInstance",
                            MethodType.methodType(boolean.class, Object.class)),
                    MethodType.methodType(boolean.class, Object.class, Class.class), 1, 0);
        }
        catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Setup
    @SuppressWarnings("deprecation")
    public void setup() throws Throwable {
        Class<?>[] labels40 = Arrays.copyOf(Test.class.getPermittedSubclasses(), 40);
        Class<?>[] labels100 = Test.class.getPermittedSubclasses();

        data40 = Arrays.stream(labels40).map(c -> {
            try {
                return c.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }).toArray(s -> new Object[s]);

        data100 = Arrays.stream(labels100).map(c -> {
            try {
                return c.newInstance();
            } catch (InstantiationException | IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }).toArray(s -> new Object[s]);

        handleMethodHandleCascade40 = generateMethodHandleCascade(labels40);
        handleIfElseCascade40 = LOOKUP.findStatic(SwitchBSM.class, "doIfCascade40",
                MethodType.methodType(int.class, Object.class));
        handleConditionalCascade40 = LOOKUP.findStatic(SwitchBSM.class, "doConditionalCascade40",
                MethodType.methodType(int.class, Object.class));

        handleMethodHandleCascade100 = generateMethodHandleCascade(labels100);
        handleIfElseCascade100 = LOOKUP.findStatic(SwitchBSM.class, "doIfCascade100",
                MethodType.methodType(int.class, Object.class));
        handleConditionalCascade100 = LOOKUP.findStatic(SwitchBSM.class, "doConditionalCascade100",
                MethodType.methodType(int.class, Object.class));
        handleLoop100 = MethodHandles.insertArguments(LOOKUP.findStatic(SwitchBSM.class, "categorizeByLoop",
                MethodType.methodType(int.class, Object.class, Class[].class)), 1, (Object) labels100);

        handleLoopList100 = MethodHandles.insertArguments(LOOKUP.findStatic(SwitchBSM.class, "categorizeByLoopList",
                MethodType.methodType(int.class, Object.class, List.class)), 1, (Object) List.of(labels100));
    }

    private static MethodHandle generateMethodHandleCascade(Class<?>[] labels) {
        MethodHandle def = MethodHandles.dropArguments(MethodHandles.constant(int.class, labels.length), 0, Object.class);
        List<Class<?>> labelsList = List.of(labels).reversed();
        MethodHandle test = def;
        int idx = labels.length - 1;

        for (int j = 0; j < labelsList.size(); j++, idx--) {
            Object currentLabel = labelsList.get(j);
            if (j + 1 < labelsList.size() && labelsList.get(j + 1) == currentLabel) continue;
            MethodHandle currentTest = INSTANCEOF_CHECK;
            test = MethodHandles.guardWithTest(MethodHandles.insertArguments(currentTest, 1, currentLabel),
                    MethodHandles.dropArguments(MethodHandles.constant(int.class, idx), 0, Object.class),
                    test);
        }

        return test;
    }
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SwitchBSM.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}

sealed interface Test {
    public record R00() implements Test {}
    public record R01() implements Test {}
    public record R02() implements Test {}
    public record R03() implements Test {}
    public record R04() implements Test {}
    public record R05() implements Test {}
    public record R06() implements Test {}
    public record R07() implements Test {}
    public record R08() implements Test {}
    public record R09() implements Test {}

    public record R10() implements Test {}
    public record R11() implements Test {}
    public record R12() implements Test {}
    public record R13() implements Test {}
    public record R14() implements Test {}
    public record R15() implements Test {}
    public record R16() implements Test {}
    public record R17() implements Test {}
    public record R18() implements Test {}
    public record R19() implements Test {}

    public record R20() implements Test {}
    public record R21() implements Test {}
    public record R22() implements Test {}
    public record R23() implements Test {}
    public record R24() implements Test {}
    public record R25() implements Test {}
    public record R26() implements Test {}
    public record R27() implements Test {}
    public record R28() implements Test {}
    public record R29() implements Test {}

    public record R30() implements Test {}
    public record R31() implements Test {}
    public record R32() implements Test {}
    public record R33() implements Test {}
    public record R34() implements Test {}
    public record R35() implements Test {}
    public record R36() implements Test {}
    public record R37() implements Test {}
    public record R38() implements Test {}
    public record R39() implements Test {}

    public record R40() implements Test {}
    public record R41() implements Test {}
    public record R42() implements Test {}
    public record R43() implements Test {}
    public record R44() implements Test {}
    public record R45() implements Test {}
    public record R46() implements Test {}
    public record R47() implements Test {}
    public record R48() implements Test {}
    public record R49() implements Test {}

    public record R50() implements Test {}
    public record R51() implements Test {}
    public record R52() implements Test {}
    public record R53() implements Test {}
    public record R54() implements Test {}
    public record R55() implements Test {}
    public record R56() implements Test {}
    public record R57() implements Test {}
    public record R58() implements Test {}
    public record R59() implements Test {}

    public record R60() implements Test {}
    public record R61() implements Test {}
    public record R62() implements Test {}
    public record R63() implements Test {}
    public record R64() implements Test {}
    public record R65() implements Test {}
    public record R66() implements Test {}
    public record R67() implements Test {}
    public record R68() implements Test {}
    public record R69() implements Test {}

    public record R70() implements Test {}
    public record R71() implements Test {}
    public record R72() implements Test {}
    public record R73() implements Test {}
    public record R74() implements Test {}
    public record R75() implements Test {}
    public record R76() implements Test {}
    public record R77() implements Test {}
    public record R78() implements Test {}
    public record R79() implements Test {}

    public record R80() implements Test {}
    public record R81() implements Test {}
    public record R82() implements Test {}
    public record R83() implements Test {}
    public record R84() implements Test {}
    public record R85() implements Test {}
    public record R86() implements Test {}
    public record R87() implements Test {}
    public record R88() implements Test {}
    public record R89() implements Test {}

    public record R90() implements Test {}
    public record R91() implements Test {}
    public record R92() implements Test {}
    public record R93() implements Test {}
    public record R94() implements Test {}
    public record R95() implements Test {}
    public record R96() implements Test {}
    public record R97() implements Test {}
    public record R98() implements Test {}
    public record R99() implements Test {}
    interface I<T> {}
    record Infinite<T>(Integer value) {}
}
