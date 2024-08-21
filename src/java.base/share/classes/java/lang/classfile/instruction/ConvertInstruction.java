/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package java.lang.classfile.instruction;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import jdk.internal.classfile.impl.AbstractInstruction;
import jdk.internal.classfile.impl.BytecodeHelpers;
import jdk.internal.javac.PreviewFeature;

import static jdk.internal.classfile.impl.AbstractInstruction.UnboundConvertInstruction.*;

/**
 * Models a primitive conversion instruction in the {@code code} array of a
 * {@code Code} attribute, such as {@code i2l}.  Corresponding opcodes will have
 * a {@code kind} of {@link Opcode.Kind#CONVERT}.  Delivered as a {@link
 * CodeElement} when traversing the elements of a {@link CodeModel}.
 *
 * @since 22
 */
@PreviewFeature(feature = PreviewFeature.Feature.CLASSFILE_API)
public sealed interface ConvertInstruction extends Instruction
        permits AbstractInstruction.UnboundConvertInstruction {
    /**
     * {@return the source type to convert from}
     */
    TypeKind fromType();

    /**
     * {@return the destination type to convert to}
     */
    TypeKind toType();

    /**
     * {@return A conversion instruction}
     *
     * @param fromType the type to convert from
     * @param toType the type to convert to
     */
    static ConvertInstruction of(TypeKind fromType, TypeKind toType) {
        return of(BytecodeHelpers.convertOpcode(fromType, toType));
    }

    /**
     * {@return a conversion instruction}
     *
     * @param op the opcode for the specific type of conversion instruction,
     *           which must be of kind {@link Opcode.Kind#CONVERT}
     * @throws IllegalArgumentException if the opcode kind is not
     *         {@link Opcode.Kind#CONVERT}.
     */
    static ConvertInstruction of(Opcode op) {
        return switch (op.bytecode()) {
            case ClassFile.I2L -> AbstractInstruction.UnboundConvertInstruction.I2L;
            case ClassFile.I2F -> AbstractInstruction.UnboundConvertInstruction.I2F;
            case ClassFile.I2D -> AbstractInstruction.UnboundConvertInstruction.I2D;
            case ClassFile.L2I -> AbstractInstruction.UnboundConvertInstruction.L2I;
            case ClassFile.L2F -> AbstractInstruction.UnboundConvertInstruction.L2F;
            case ClassFile.L2D -> AbstractInstruction.UnboundConvertInstruction.L2D;
            case ClassFile.F2I -> AbstractInstruction.UnboundConvertInstruction.F2I;
            case ClassFile.F2L -> AbstractInstruction.UnboundConvertInstruction.F2L;
            case ClassFile.F2D -> AbstractInstruction.UnboundConvertInstruction.F2D;
            case ClassFile.D2I -> AbstractInstruction.UnboundConvertInstruction.D2I;
            case ClassFile.D2L -> AbstractInstruction.UnboundConvertInstruction.D2L;
            case ClassFile.D2F -> AbstractInstruction.UnboundConvertInstruction.D2F;
            case ClassFile.I2B -> AbstractInstruction.UnboundConvertInstruction.I2B;
            case ClassFile.I2C -> AbstractInstruction.UnboundConvertInstruction.I2C;
            case ClassFile.I2S -> AbstractInstruction.UnboundConvertInstruction.I2S;
            default -> throw new IllegalArgumentException(String.format("Wrong opcode specified; found %s, expected Opcode.Kind.CONVERT", op));
        };
    }
}
