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
import jdk.internal.javac.PreviewFeature;

import static jdk.internal.classfile.impl.AbstractInstruction.UnboundOperatorInstruction.*;

/**
 * Models an arithmetic operator instruction in the {@code code} array of a
 * {@code Code} attribute.  Corresponding opcodes will have a {@code kind} of
 * {@link Opcode.Kind#OPERATOR}.  Delivered as a {@link CodeElement} when
 * traversing the elements of a {@link CodeModel}.
 *
 * @since 22
 */
@PreviewFeature(feature = PreviewFeature.Feature.CLASSFILE_API)
public sealed interface OperatorInstruction extends Instruction
        permits AbstractInstruction.UnboundOperatorInstruction {
    /**
     * {@return the operand type of the instruction}
     */
    TypeKind typeKind();

    /**
     * {@return an operator instruction}
     *
     * @param op the opcode for the specific type of array load instruction,
     *           which must be of kind {@link Opcode.Kind#OPERATOR}
     * @throws IllegalArgumentException if the opcode kind is not
     *         {@link Opcode.Kind#OPERATOR}.
     */
    static OperatorInstruction of(Opcode op) {
        return switch (op.bytecode()) {
            case ClassFile.IADD -> AbstractInstruction.UnboundOperatorInstruction.IADD;
            case ClassFile.LADD -> AbstractInstruction.UnboundOperatorInstruction.LADD;
            case ClassFile.FADD -> AbstractInstruction.UnboundOperatorInstruction.FADD;
            case ClassFile.DADD -> AbstractInstruction.UnboundOperatorInstruction.DADD;
            case ClassFile.ISUB -> AbstractInstruction.UnboundOperatorInstruction.ISUB;
            case ClassFile.LSUB -> AbstractInstruction.UnboundOperatorInstruction.LSUB;
            case ClassFile.FSUB -> AbstractInstruction.UnboundOperatorInstruction.FSUB;
            case ClassFile.DSUB -> AbstractInstruction.UnboundOperatorInstruction.DSUB;
            case ClassFile.IMUL -> AbstractInstruction.UnboundOperatorInstruction.IMUL;
            case ClassFile.LMUL -> AbstractInstruction.UnboundOperatorInstruction.LMUL;
            case ClassFile.FMUL -> AbstractInstruction.UnboundOperatorInstruction.FMUL;
            case ClassFile.DMUL -> AbstractInstruction.UnboundOperatorInstruction.DMUL;
            case ClassFile.IDIV -> AbstractInstruction.UnboundOperatorInstruction.IDIV;
            case ClassFile.LDIV -> AbstractInstruction.UnboundOperatorInstruction.LDIV;
            case ClassFile.FDIV -> AbstractInstruction.UnboundOperatorInstruction.FDIV;
            case ClassFile.DDIV -> AbstractInstruction.UnboundOperatorInstruction.DDIV;
            case ClassFile.IREM -> AbstractInstruction.UnboundOperatorInstruction.IREM;
            case ClassFile.LREM -> AbstractInstruction.UnboundOperatorInstruction.LREM;
            case ClassFile.FREM -> AbstractInstruction.UnboundOperatorInstruction.FREM;
            case ClassFile.DREM -> AbstractInstruction.UnboundOperatorInstruction.DREM;
            case ClassFile.INEG -> AbstractInstruction.UnboundOperatorInstruction.INEG;
            case ClassFile.LNEG -> AbstractInstruction.UnboundOperatorInstruction.LNEG;
            case ClassFile.FNEG -> AbstractInstruction.UnboundOperatorInstruction.FNEG;
            case ClassFile.DNEG -> AbstractInstruction.UnboundOperatorInstruction.DNEG;
            case ClassFile.ISHL -> AbstractInstruction.UnboundOperatorInstruction.ISHL;
            case ClassFile.LSHL -> AbstractInstruction.UnboundOperatorInstruction.LSHL;
            case ClassFile.ISHR -> AbstractInstruction.UnboundOperatorInstruction.ISHR;
            case ClassFile.LSHR -> AbstractInstruction.UnboundOperatorInstruction.LSHR;
            case ClassFile.IUSHR -> AbstractInstruction.UnboundOperatorInstruction.IUSHR;
            case ClassFile.LUSHR -> AbstractInstruction.UnboundOperatorInstruction.LUSHR;
            case ClassFile.IAND -> AbstractInstruction.UnboundOperatorInstruction.IAND;
            case ClassFile.LAND -> AbstractInstruction.UnboundOperatorInstruction.LAND;
            case ClassFile.IOR -> AbstractInstruction.UnboundOperatorInstruction.IOR;
            case ClassFile.LOR -> AbstractInstruction.UnboundOperatorInstruction.LOR;
            case ClassFile.IXOR -> AbstractInstruction.UnboundOperatorInstruction.IXOR;
            case ClassFile.LXOR -> AbstractInstruction.UnboundOperatorInstruction.LXOR;
            case ClassFile.LCMP -> AbstractInstruction.UnboundOperatorInstruction.LCMP;
            case ClassFile.FCMPL -> AbstractInstruction.UnboundOperatorInstruction.FCMPL;
            case ClassFile.FCMPG -> AbstractInstruction.UnboundOperatorInstruction.FCMPG;
            case ClassFile.DCMPL -> AbstractInstruction.UnboundOperatorInstruction.DCMPL;
            case ClassFile.DCMPG -> AbstractInstruction.UnboundOperatorInstruction.DCMPG;
            case ClassFile.ARRAYLENGTH -> AbstractInstruction.UnboundOperatorInstruction.ARRAYLENGTH;
            default -> throw new IllegalArgumentException("Unknown opcode specified: " + op);
        };
    }
}
