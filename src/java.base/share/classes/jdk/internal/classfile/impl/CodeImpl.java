/*
 * Copyright (c) 2022, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.classfile.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import java.lang.classfile.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.StackMapTableAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.*;

import static java.lang.classfile.ClassFile.*;

public final class CodeImpl
        extends BoundAttribute.BoundCodeAttribute
        implements LabelContext {

    List<ExceptionCatch> exceptionTable;
    List<Attribute<?>> attributes;

    // Inflated for iteration
    LabelImpl[] labels;
    int[] lineNumbers;
    boolean inflated;

    public CodeImpl(AttributedElement enclosing,
                    ClassReader reader,
                    AttributeMapper<CodeAttribute> mapper,
                    int payloadStart) {
        super(enclosing, reader, mapper, payloadStart);
    }

    // LabelContext

    @Override
    public Label newLabel() {
        throw new UnsupportedOperationException("CodeAttribute only supports fixed labels");
    }

    @Override
    public void setLabelTarget(Label label, int bci) {
        throw new UnsupportedOperationException("CodeAttribute only supports fixed labels");
    }

    @Override
    public Label getLabel(int bci) {
        if (bci < 0 || bci > codeLength)
            throw new IllegalArgumentException(String.format("Bytecode offset out of range; bci=%d, codeLength=%d",
                                                             bci, codeLength));
        if (labels == null)
            labels = new LabelImpl[codeLength + 1];
        LabelImpl l = labels[bci];
        if (l == null)
            l = labels[bci] = new LabelImpl(this, bci);
        return l;
    }

    @Override
    public int labelToBci(Label label) {
        LabelImpl lab = (LabelImpl) label;
        if (lab.labelContext() != this)
            throw new IllegalArgumentException(String.format("Illegal label reuse; context=%s, label=%s",
                                                             this, lab.labelContext()));
        return lab.getBCI();
    }

    private void inflateMetadata() {
        if (!inflated) {
            if (labels == null)
                labels = new LabelImpl[codeLength + 1];
            if (classReader.context().lineNumbersOption() == ClassFile.LineNumbersOption.PASS_LINE_NUMBERS)
                inflateLineNumbers();
            inflateJumpTargets();
            inflateTypeAnnotations();
            inflated = true;
        }
    }

    // CodeAttribute

    @Override
    public List<Attribute<?>> attributes() {
        if (attributes == null) {
            attributes = BoundAttribute.readAttributes(this, classReader, attributePos, classReader.customAttributes());
        }
        return attributes;
    }

    @Override
    public void writeTo(BufWriterImpl buf) {
        if (buf.canWriteDirect(classReader)) {
            super.writeTo(buf);
        }
        else {
            DirectCodeBuilder.build((MethodInfo) enclosingMethod,
                                    new Consumer<CodeBuilder>() {
                                        @Override
                                        public void accept(CodeBuilder cb) {
                                            forEach(cb);
                                        }
                                    },
                                    (SplitConstantPool)buf.constantPool(),
                                    buf.context(),
                                    null).writeTo(buf);
        }
    }

    // CodeModel

    @Override
    public Optional<MethodModel> parent() {
        return Optional.of(enclosingMethod);
    }

    @Override
    public void forEach(Consumer<? super CodeElement> consumer) {
        Objects.requireNonNull(consumer);
        inflateMetadata();
        boolean doLineNumbers = (lineNumbers != null);
        generateCatchTargets(consumer);
        if (classReader.context().debugElementsOption() == ClassFile.DebugElementsOption.PASS_DEBUG)
            generateDebugElements(consumer);
        for (int pos=codeStart; pos<codeEnd; ) {
            if (labels[pos - codeStart] != null)
                consumer.accept(labels[pos - codeStart]);
            if (doLineNumbers && lineNumbers[pos - codeStart] != 0)
                consumer.accept(LineNumberImpl.of(lineNumbers[pos - codeStart]));
            int bc = classReader.readU1(pos);
            Instruction instr = bcToInstruction(bc, pos);
            consumer.accept(instr);
            pos += instr.sizeInBytes();
        }
        // There might be labels pointing to the bci at codeEnd
        if (labels[codeEnd-codeStart] != null)
            consumer.accept(labels[codeEnd - codeStart]);
        if (doLineNumbers && lineNumbers[codeEnd - codeStart] != 0)
            consumer.accept(LineNumberImpl.of(lineNumbers[codeEnd - codeStart]));
    }

    @Override
    public List<ExceptionCatch> exceptionHandlers() {
        if (exceptionTable == null) {
            inflateMetadata();
            exceptionTable = new ArrayList<>(exceptionHandlerCnt);
            iterateExceptionHandlers(new ExceptionHandlerAction() {
                @Override
                public void accept(int s, int e, int h, int c) {
                    ClassEntry catchTypeEntry = c == 0
                                                             ? null
                                                             : constantPool().entryByIndex(c, ClassEntry.class);
                    exceptionTable.add(new AbstractPseudoInstruction.ExceptionCatchImpl(getLabel(h), getLabel(s), getLabel(e), catchTypeEntry));
                }
            });
            exceptionTable = Collections.unmodifiableList(exceptionTable);
        }
        return exceptionTable;
    }

    public boolean compareCodeBytes(BufWriterImpl buf, int offset, int len) {
        return codeLength == len
               && classReader.compare(buf, offset, codeStart, codeLength);
    }

    private int adjustForObjectOrUninitialized(int bci) {
        int vt = classReader.readU1(bci);
        //inflate newTarget labels from Uninitialized VTIs
        if (vt == 8) inflateLabel(classReader.readU2(bci + 1));
        return (vt == 7 || vt == 8) ? bci + 3 : bci + 1;
    }

    private void inflateLabel(int bci) {
        if (bci < 0 || bci > codeLength)
            throw new IllegalArgumentException(String.format("Bytecode offset out of range; bci=%d, codeLength=%d",
                                                             bci, codeLength));
        if (labels[bci] == null)
            labels[bci] = new LabelImpl(this, bci);
    }

    private void inflateLineNumbers() {
        for (Attribute<?> a : attributes()) {
            if (a.attributeMapper() == Attributes.lineNumberTable()) {
                BoundLineNumberTableAttribute attr = (BoundLineNumberTableAttribute) a;
                if (lineNumbers == null)
                    lineNumbers = new int[codeLength + 1];

                int nLn = classReader.readU2(attr.payloadStart);
                int p = attr.payloadStart + 2;
                int pEnd = p + (nLn * 4);
                for (; p < pEnd; p += 4) {
                    int startPc = classReader.readU2(p);
                    if (startPc > codeLength) {
                        throw new IllegalArgumentException(String.format(
                                "Line number start_pc out of range; start_pc=%d, codeLength=%d", startPc, codeLength));
                    }
                    int lineNumber = classReader.readU2(p + 2);
                    lineNumbers[startPc] = lineNumber;
                }
            }
        }
    }

    private void inflateJumpTargets() {
        Optional<StackMapTableAttribute> a = findAttribute(Attributes.stackMapTable());
        if (a.isEmpty()) {
            if (classReader.readU2(6) <= ClassFile.JAVA_6_VERSION) {
                //fallback to jump targets inflation without StackMapTableAttribute
                for (int pos=codeStart; pos<codeEnd; ) {
                    var i = bcToInstruction(classReader.readU1(pos), pos);
                    switch (i) {
                        case BranchInstruction br -> br.target();
                        case DiscontinuedInstruction.JsrInstruction jsr -> jsr.target();
                        default -> {}
                    }
                    pos += i.sizeInBytes();
                }
            }
            return;
        }
        @SuppressWarnings("unchecked")
        int stackMapPos = ((BoundAttribute<StackMapTableAttribute>) a.get()).payloadStart;

        int bci = -1; //compensate for offsetDelta + 1
        int nEntries = classReader.readU2(stackMapPos);
        int p = stackMapPos + 2;
        for (int i = 0; i < nEntries; ++i) {
            int frameType = classReader.readU1(p);
            int offsetDelta;
            if (frameType < 64) {
                offsetDelta = frameType;
                ++p;
            }
            else if (frameType < 128) {
                offsetDelta = frameType & 0x3f;
                p = adjustForObjectOrUninitialized(p + 1);
            }
            else
                switch (frameType) {
                    case 247 -> {
                        offsetDelta = classReader.readU2(p + 1);
                        p = adjustForObjectOrUninitialized(p + 3);
                    }
                    case 248, 249, 250, 251 -> {
                        offsetDelta = classReader.readU2(p + 1);
                        p += 3;
                    }
                    case 252, 253, 254 -> {
                        offsetDelta = classReader.readU2(p + 1);
                        int k = frameType - 251;
                        p += 3;
                        for (int c = 0; c < k; ++c) {
                            p = adjustForObjectOrUninitialized(p);
                        }
                    }
                    case 255 -> {
                        offsetDelta = classReader.readU2(p + 1);
                        p += 3;
                        int k = classReader.readU2(p);
                        p += 2;
                        for (int c = 0; c < k; ++c) {
                            p = adjustForObjectOrUninitialized(p);
                        }
                        k = classReader.readU2(p);
                        p += 2;
                        for (int c = 0; c < k; ++c) {
                            p = adjustForObjectOrUninitialized(p);
                        }
                    }
                    default -> throw new IllegalArgumentException("Bad frame type: " + frameType);
                }
            bci += offsetDelta + 1;
            inflateLabel(bci);
        }
    }

    private void inflateTypeAnnotations() {
        findAttribute(Attributes.runtimeVisibleTypeAnnotations()).ifPresent(RuntimeVisibleTypeAnnotationsAttribute::annotations);
        findAttribute(Attributes.runtimeInvisibleTypeAnnotations()).ifPresent(RuntimeInvisibleTypeAnnotationsAttribute::annotations);
    }

    private void generateCatchTargets(Consumer<? super CodeElement> consumer) {
        // We attach all catch targets to bci zero, because trying to attach them
        // to their range could subtly affect the order of exception processing
        iterateExceptionHandlers(new ExceptionHandlerAction() {
            @Override
            public void accept(int s, int e, int h, int c) {
                ClassEntry catchType = c == 0
                                                    ? null
                                                    : classReader.entryByIndex(c, ClassEntry.class);
                consumer.accept(new AbstractPseudoInstruction.ExceptionCatchImpl(getLabel(h), getLabel(s), getLabel(e), catchType));
            }
        });
    }

    private void generateDebugElements(Consumer<? super CodeElement> consumer) {
        for (Attribute<?> a : attributes()) {
            if (a.attributeMapper() == Attributes.characterRangeTable()) {
                var attr = (BoundCharacterRangeTableAttribute) a;
                int cnt = classReader.readU2(attr.payloadStart);
                int p = attr.payloadStart + 2;
                int pEnd = p + (cnt * 14);
                for (; p < pEnd; p += 14) {
                    var instruction = new BoundCharacterRange(this, p);
                    inflateLabel(instruction.startPc());
                    inflateLabel(instruction.endPc() + 1);
                    consumer.accept(instruction);
                }
            }
            else if (a.attributeMapper() == Attributes.localVariableTable()) {
                var attr = (BoundLocalVariableTableAttribute) a;
                int cnt = classReader.readU2(attr.payloadStart);
                int p = attr.payloadStart + 2;
                int pEnd = p + (cnt * 10);
                for (; p < pEnd; p += 10) {
                    BoundLocalVariable instruction = new BoundLocalVariable(this, p);
                    inflateLabel(instruction.startPc());
                    inflateLabel(instruction.startPc() + instruction.length());
                    consumer.accept(instruction);
                }
            }
            else if (a.attributeMapper() == Attributes.localVariableTypeTable()) {
                var attr = (BoundLocalVariableTypeTableAttribute) a;
                int cnt = classReader.readU2(attr.payloadStart);
                int p = attr.payloadStart + 2;
                int pEnd = p + (cnt * 10);
                for (; p < pEnd; p += 10) {
                    BoundLocalVariableType instruction = new BoundLocalVariableType(this, p);
                    inflateLabel(instruction.startPc());
                    inflateLabel(instruction.startPc() + instruction.length());
                    consumer.accept(instruction);
                }
            }
            else if (a.attributeMapper() == Attributes.runtimeVisibleTypeAnnotations()) {
                consumer.accept((BoundRuntimeVisibleTypeAnnotationsAttribute) a);
            }
            else if (a.attributeMapper() == Attributes.runtimeInvisibleTypeAnnotations()) {
                consumer.accept((BoundRuntimeInvisibleTypeAnnotationsAttribute) a);
            }
        }
    }

    public interface ExceptionHandlerAction {
        void accept(int start, int end, int handler, int catchTypeIndex);
    }

    public void iterateExceptionHandlers(ExceptionHandlerAction a) {
        int p = exceptionHandlerPos + 2;
        for (int i = 0; i < exceptionHandlerCnt; ++i) {
            a.accept(classReader.readU2(p), classReader.readU2(p + 2), classReader.readU2(p + 4), classReader.readU2(p + 6));
            p += 8;
        }
    }

    private Instruction bcToInstruction(int bc, int pos) {
        return switch (bc) {
            case BIPUSH -> new AbstractInstruction.BoundArgumentConstantInstruction(Opcode.BIPUSH, CodeImpl.this, pos);
            case SIPUSH -> new AbstractInstruction.BoundArgumentConstantInstruction(Opcode.SIPUSH, CodeImpl.this, pos);
            case LDC -> new AbstractInstruction.BoundLoadConstantInstruction(Opcode.LDC, CodeImpl.this, pos);
            case LDC_W -> new AbstractInstruction.BoundLoadConstantInstruction(Opcode.LDC_W, CodeImpl.this, pos);
            case LDC2_W -> new AbstractInstruction.BoundLoadConstantInstruction(Opcode.LDC2_W, CodeImpl.this, pos);
            case ILOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.ILOAD, CodeImpl.this, pos);
            case LLOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.LLOAD, CodeImpl.this, pos);
            case FLOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.FLOAD, CodeImpl.this, pos);
            case DLOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.DLOAD, CodeImpl.this, pos);
            case ALOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.ALOAD, CodeImpl.this, pos);
            case ISTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.ISTORE, CodeImpl.this, pos);
            case LSTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.LSTORE, CodeImpl.this, pos);
            case FSTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.FSTORE, CodeImpl.this, pos);
            case DSTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.DSTORE, CodeImpl.this, pos);
            case ASTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.ASTORE, CodeImpl.this, pos);
            case IINC -> new AbstractInstruction.BoundIncrementInstruction(Opcode.IINC, CodeImpl.this, pos);
            case IFEQ -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFEQ, CodeImpl.this, pos);
            case IFNE -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFNE, CodeImpl.this, pos);
            case IFLT -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFLT, CodeImpl.this, pos);
            case IFGE -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFGE, CodeImpl.this, pos);
            case IFGT -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFGT, CodeImpl.this, pos);
            case IFLE -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFLE, CodeImpl.this, pos);
            case IF_ICMPEQ -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ICMPEQ, CodeImpl.this, pos);
            case IF_ICMPNE -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ICMPNE, CodeImpl.this, pos);
            case IF_ICMPLT -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ICMPLT, CodeImpl.this, pos);
            case IF_ICMPGE -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ICMPGE, CodeImpl.this, pos);
            case IF_ICMPGT -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ICMPGT, CodeImpl.this, pos);
            case IF_ICMPLE -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ICMPLE, CodeImpl.this, pos);
            case IF_ACMPEQ -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ACMPEQ, CodeImpl.this, pos);
            case IF_ACMPNE -> new AbstractInstruction.BoundBranchInstruction(Opcode.IF_ACMPNE, CodeImpl.this, pos);
            case GOTO -> new AbstractInstruction.BoundBranchInstruction(Opcode.GOTO, CodeImpl.this, pos);
            case TABLESWITCH -> new AbstractInstruction.BoundTableSwitchInstruction(Opcode.TABLESWITCH, CodeImpl.this, pos);
            case LOOKUPSWITCH -> new AbstractInstruction.BoundLookupSwitchInstruction(Opcode.LOOKUPSWITCH, CodeImpl.this, pos);
            case GETSTATIC -> new AbstractInstruction.BoundFieldInstruction(Opcode.GETSTATIC, CodeImpl.this, pos);
            case PUTSTATIC -> new AbstractInstruction.BoundFieldInstruction(Opcode.PUTSTATIC, CodeImpl.this, pos);
            case GETFIELD -> new AbstractInstruction.BoundFieldInstruction(Opcode.GETFIELD, CodeImpl.this, pos);
            case PUTFIELD -> new AbstractInstruction.BoundFieldInstruction(Opcode.PUTFIELD, CodeImpl.this, pos);
            case INVOKEVIRTUAL -> new AbstractInstruction.BoundInvokeInstruction(Opcode.INVOKEVIRTUAL, CodeImpl.this, pos);
            case INVOKESPECIAL -> new AbstractInstruction.BoundInvokeInstruction(Opcode.INVOKESPECIAL, CodeImpl.this, pos);
            case INVOKESTATIC -> new AbstractInstruction.BoundInvokeInstruction(Opcode.INVOKESTATIC, CodeImpl.this, pos);
            case INVOKEINTERFACE -> new AbstractInstruction.BoundInvokeInterfaceInstruction(Opcode.INVOKEINTERFACE, CodeImpl.this, pos);
            case INVOKEDYNAMIC -> new AbstractInstruction.BoundInvokeDynamicInstruction(Opcode.INVOKEDYNAMIC, CodeImpl.this, pos);
            case NEW -> new AbstractInstruction.BoundNewObjectInstruction(CodeImpl.this, pos);
            case NEWARRAY -> new AbstractInstruction.BoundNewPrimitiveArrayInstruction(Opcode.NEWARRAY, CodeImpl.this, pos);
            case ANEWARRAY -> new AbstractInstruction.BoundNewReferenceArrayInstruction(Opcode.ANEWARRAY, CodeImpl.this, pos);
            case CHECKCAST -> new AbstractInstruction.BoundTypeCheckInstruction(Opcode.CHECKCAST, CodeImpl.this, pos);
            case INSTANCEOF -> new AbstractInstruction.BoundTypeCheckInstruction(Opcode.INSTANCEOF, CodeImpl.this, pos);

            case WIDE -> {
                int bclow = classReader.readU1(pos + 1);
                yield switch (bclow) {
                    case ILOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.ILOAD_W, this, pos);
                    case LLOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.LLOAD_W, this, pos);
                    case FLOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.FLOAD_W, this, pos);
                    case DLOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.DLOAD_W, this, pos);
                    case ALOAD -> new AbstractInstruction.BoundLoadInstruction(Opcode.ALOAD_W, this, pos);
                    case ISTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.ISTORE_W, this, pos);
                    case LSTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.LSTORE_W, this, pos);
                    case FSTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.FSTORE_W, this, pos);
                    case DSTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.DSTORE_W, this, pos);
                    case ASTORE -> new AbstractInstruction.BoundStoreInstruction(Opcode.ASTORE_W, this, pos);
                    case IINC -> new AbstractInstruction.BoundIncrementInstruction(Opcode.IINC_W, this, pos);
                    case RET ->  new AbstractInstruction.BoundRetInstruction(Opcode.RET_W, this, pos);
                    default -> throw new IllegalArgumentException("unknown wide instruction: " + bclow);
                };
            }

            case MULTIANEWARRAY -> new AbstractInstruction.BoundNewMultidimensionalArrayInstruction(Opcode.MULTIANEWARRAY, CodeImpl.this, pos);
            case IFNULL -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFNULL, CodeImpl.this, pos);
            case IFNONNULL -> new AbstractInstruction.BoundBranchInstruction(Opcode.IFNONNULL, CodeImpl.this, pos);
            case GOTO_W -> new AbstractInstruction.BoundBranchInstruction(Opcode.GOTO_W, CodeImpl.this, pos);

            case JSR -> new AbstractInstruction.BoundJsrInstruction(Opcode.JSR, CodeImpl.this, pos);
            case RET ->  new AbstractInstruction.BoundRetInstruction(Opcode.RET, this, pos);
            case JSR_W -> new AbstractInstruction.BoundJsrInstruction(Opcode.JSR_W, CodeImpl.this, pos);

            // Singleton instructions
            case ATHROW -> AbstractInstruction.UnboundThrowInstruction.ATHROW;
            case NOP -> AbstractInstruction.UnboundNopInstruction.NOP;
            case IALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.IALOAD;
            case LALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.LALOAD;
            case FALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.FALOAD;
            case DALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.DALOAD;
            case AALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.AALOAD;
            case BALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.BALOAD;
            case CALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.CALOAD;
            case SALOAD -> AbstractInstruction.UnboundArrayLoadInstruction.SALOAD;
            case IASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.IASTORE;
            case LASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.LASTORE;
            case FASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.FASTORE;
            case DASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.DASTORE;
            case AASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.AASTORE;
            case BASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.BASTORE;
            case CASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.CASTORE;
            case SASTORE -> AbstractInstruction.UnboundArrayStoreInstruction.SASTORE;
            case ACONST_NULL -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ACONST_NULL;
            case ICONST_M1 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ICONST_M1;
            case ICONST_0 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ICONST_0;
            case ICONST_1 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ICONST_1;
            case ICONST_2 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ICONST_2;
            case ICONST_3 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ICONST_3;
            case ICONST_4 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ICONST_4;
            case ICONST_5 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.ICONST_5;
            case LCONST_0 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.LCONST_0;
            case LCONST_1 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.LCONST_1;
            case FCONST_0 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.FCONST_0;
            case FCONST_1 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.FCONST_1;
            case FCONST_2 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.FCONST_2;
            case DCONST_0 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.DCONST_0;
            case DCONST_1 -> AbstractInstruction.UnboundIntrinsicConstantInstruction.DCONST_1;
            case I2L -> AbstractInstruction.UnboundConvertInstruction.I2L;
            case I2F -> AbstractInstruction.UnboundConvertInstruction.I2F;
            case I2D -> AbstractInstruction.UnboundConvertInstruction.I2D;
            case L2I -> AbstractInstruction.UnboundConvertInstruction.L2I;
            case L2F -> AbstractInstruction.UnboundConvertInstruction.L2F;
            case L2D -> AbstractInstruction.UnboundConvertInstruction.L2D;
            case F2I -> AbstractInstruction.UnboundConvertInstruction.F2I;
            case F2L -> AbstractInstruction.UnboundConvertInstruction.F2L;
            case F2D -> AbstractInstruction.UnboundConvertInstruction.F2D;
            case D2I -> AbstractInstruction.UnboundConvertInstruction.D2I;
            case D2L -> AbstractInstruction.UnboundConvertInstruction.D2L;
            case D2F -> AbstractInstruction.UnboundConvertInstruction.D2F;
            case I2B -> AbstractInstruction.UnboundConvertInstruction.I2B;
            case I2C -> AbstractInstruction.UnboundConvertInstruction.I2C;
            case I2S -> AbstractInstruction.UnboundConvertInstruction.I2S;
            case ILOAD_0 -> AbstractInstruction.UnboundLoadInstruction.ILOAD_0;
            case ILOAD_1 -> AbstractInstruction.UnboundLoadInstruction.ILOAD_1;
            case ILOAD_2 -> AbstractInstruction.UnboundLoadInstruction.ILOAD_2;
            case ILOAD_3 -> AbstractInstruction.UnboundLoadInstruction.ILOAD_3;
            case LLOAD_0 -> AbstractInstruction.UnboundLoadInstruction.LLOAD_0;
            case LLOAD_1 -> AbstractInstruction.UnboundLoadInstruction.LLOAD_1;
            case LLOAD_2 -> AbstractInstruction.UnboundLoadInstruction.LLOAD_2;
            case LLOAD_3 -> AbstractInstruction.UnboundLoadInstruction.LLOAD_3;
            case FLOAD_0 -> AbstractInstruction.UnboundLoadInstruction.FLOAD_0;
            case FLOAD_1 -> AbstractInstruction.UnboundLoadInstruction.FLOAD_1;
            case FLOAD_2 -> AbstractInstruction.UnboundLoadInstruction.FLOAD_2;
            case FLOAD_3 -> AbstractInstruction.UnboundLoadInstruction.FLOAD_3;
            case DLOAD_0 -> AbstractInstruction.UnboundLoadInstruction.DLOAD_0;
            case DLOAD_1 -> AbstractInstruction.UnboundLoadInstruction.DLOAD_1;
            case DLOAD_2 -> AbstractInstruction.UnboundLoadInstruction.DLOAD_2;
            case DLOAD_3 -> AbstractInstruction.UnboundLoadInstruction.DLOAD_3;
            case ALOAD_0 -> AbstractInstruction.UnboundLoadInstruction.ALOAD_0;
            case ALOAD_1 -> AbstractInstruction.UnboundLoadInstruction.ALOAD_1;
            case ALOAD_2 -> AbstractInstruction.UnboundLoadInstruction.ALOAD_2;
            case ALOAD_3 -> AbstractInstruction.UnboundLoadInstruction.ALOAD_3;
            case MONITORENTER -> AbstractInstruction.UnboundMonitorInstruction.MONITORENTER;
            case MONITOREXIT -> AbstractInstruction.UnboundMonitorInstruction.MONITOREXIT;
            case IADD -> AbstractInstruction.UnboundOperatorInstruction.IADD;
            case LADD -> AbstractInstruction.UnboundOperatorInstruction.LADD;
            case FADD -> AbstractInstruction.UnboundOperatorInstruction.FADD;
            case DADD -> AbstractInstruction.UnboundOperatorInstruction.DADD;
            case ISUB -> AbstractInstruction.UnboundOperatorInstruction.ISUB;
            case LSUB -> AbstractInstruction.UnboundOperatorInstruction.LSUB;
            case FSUB -> AbstractInstruction.UnboundOperatorInstruction.FSUB;
            case DSUB -> AbstractInstruction.UnboundOperatorInstruction.DSUB;
            case IMUL -> AbstractInstruction.UnboundOperatorInstruction.IMUL;
            case LMUL -> AbstractInstruction.UnboundOperatorInstruction.LMUL;
            case FMUL -> AbstractInstruction.UnboundOperatorInstruction.FMUL;
            case DMUL -> AbstractInstruction.UnboundOperatorInstruction.DMUL;
            case IDIV -> AbstractInstruction.UnboundOperatorInstruction.IDIV;
            case LDIV -> AbstractInstruction.UnboundOperatorInstruction.LDIV;
            case FDIV -> AbstractInstruction.UnboundOperatorInstruction.FDIV;
            case DDIV -> AbstractInstruction.UnboundOperatorInstruction.DDIV;
            case IREM -> AbstractInstruction.UnboundOperatorInstruction.IREM;
            case LREM -> AbstractInstruction.UnboundOperatorInstruction.LREM;
            case FREM -> AbstractInstruction.UnboundOperatorInstruction.FREM;
            case DREM -> AbstractInstruction.UnboundOperatorInstruction.DREM;
            case INEG -> AbstractInstruction.UnboundOperatorInstruction.INEG;
            case LNEG -> AbstractInstruction.UnboundOperatorInstruction.LNEG;
            case FNEG -> AbstractInstruction.UnboundOperatorInstruction.FNEG;
            case DNEG -> AbstractInstruction.UnboundOperatorInstruction.DNEG;
            case ISHL -> AbstractInstruction.UnboundOperatorInstruction.ISHL;
            case LSHL -> AbstractInstruction.UnboundOperatorInstruction.LSHL;
            case ISHR -> AbstractInstruction.UnboundOperatorInstruction.ISHR;
            case LSHR -> AbstractInstruction.UnboundOperatorInstruction.LSHR;
            case IUSHR -> AbstractInstruction.UnboundOperatorInstruction.IUSHR;
            case LUSHR -> AbstractInstruction.UnboundOperatorInstruction.LUSHR;
            case IAND -> AbstractInstruction.UnboundOperatorInstruction.IAND;
            case LAND -> AbstractInstruction.UnboundOperatorInstruction.LAND;
            case IOR -> AbstractInstruction.UnboundOperatorInstruction.IOR;
            case LOR -> AbstractInstruction.UnboundOperatorInstruction.LOR;
            case IXOR -> AbstractInstruction.UnboundOperatorInstruction.IXOR;
            case LXOR -> AbstractInstruction.UnboundOperatorInstruction.LXOR;
            case LCMP -> AbstractInstruction.UnboundOperatorInstruction.LCMP;
            case FCMPL -> AbstractInstruction.UnboundOperatorInstruction.FCMPL;
            case FCMPG -> AbstractInstruction.UnboundOperatorInstruction.FCMPG;
            case DCMPL -> AbstractInstruction.UnboundOperatorInstruction.DCMPL;
            case DCMPG -> AbstractInstruction.UnboundOperatorInstruction.DCMPG;
            case ARRAYLENGTH -> AbstractInstruction.UnboundOperatorInstruction.ARRAYLENGTH;
            case IRETURN -> AbstractInstruction.UnboundReturnInstruction.IRETURN;
            case LRETURN -> AbstractInstruction.UnboundReturnInstruction.LRETURN;
            case FRETURN -> AbstractInstruction.UnboundReturnInstruction.FRETURN;
            case DRETURN -> AbstractInstruction.UnboundReturnInstruction.DRETURN;
            case ARETURN -> AbstractInstruction.UnboundReturnInstruction.ARETURN;
            case RETURN -> AbstractInstruction.UnboundReturnInstruction.RETURN;
            case POP -> AbstractInstruction.UnboundStackInstruction.POP;
            case POP2 -> AbstractInstruction.UnboundStackInstruction.POP2;
            case DUP -> AbstractInstruction.UnboundStackInstruction.DUP;
            case DUP_X1 -> AbstractInstruction.UnboundStackInstruction.DUP_X1;
            case DUP_X2 -> AbstractInstruction.UnboundStackInstruction.DUP_X2;
            case DUP2 -> AbstractInstruction.UnboundStackInstruction.DUP2;
            case DUP2_X1 -> AbstractInstruction.UnboundStackInstruction.DUP2_X1;
            case DUP2_X2 -> AbstractInstruction.UnboundStackInstruction.DUP2_X2;
            case SWAP -> AbstractInstruction.UnboundStackInstruction.SWAP;
            case ISTORE_0 -> AbstractInstruction.UnboundStoreInstruction.ISTORE_0;
            case ISTORE_1 -> AbstractInstruction.UnboundStoreInstruction.ISTORE_1;
            case ISTORE_2 -> AbstractInstruction.UnboundStoreInstruction.ISTORE_2;
            case ISTORE_3 -> AbstractInstruction.UnboundStoreInstruction.ISTORE_3;
            case LSTORE_0 -> AbstractInstruction.UnboundStoreInstruction.LSTORE_0;
            case LSTORE_1 -> AbstractInstruction.UnboundStoreInstruction.LSTORE_1;
            case LSTORE_2 -> AbstractInstruction.UnboundStoreInstruction.LSTORE_2;
            case LSTORE_3 -> AbstractInstruction.UnboundStoreInstruction.LSTORE_3;
            case FSTORE_0 -> AbstractInstruction.UnboundStoreInstruction.FSTORE_0;
            case FSTORE_1 -> AbstractInstruction.UnboundStoreInstruction.FSTORE_1;
            case FSTORE_2 -> AbstractInstruction.UnboundStoreInstruction.FSTORE_2;
            case FSTORE_3 -> AbstractInstruction.UnboundStoreInstruction.FSTORE_3;
            case DSTORE_0 -> AbstractInstruction.UnboundStoreInstruction.DSTORE_0;
            case DSTORE_1 -> AbstractInstruction.UnboundStoreInstruction.DSTORE_1;
            case DSTORE_2 -> AbstractInstruction.UnboundStoreInstruction.DSTORE_2;
            case DSTORE_3 -> AbstractInstruction.UnboundStoreInstruction.DSTORE_3;
            case ASTORE_0 -> AbstractInstruction.UnboundStoreInstruction.ASTORE_0;
            case ASTORE_1 -> AbstractInstruction.UnboundStoreInstruction.ASTORE_1;
            case ASTORE_2 -> AbstractInstruction.UnboundStoreInstruction.ASTORE_2;
            case ASTORE_3 -> AbstractInstruction.UnboundStoreInstruction.ASTORE_3;
            default -> throw new IllegalArgumentException("unknown instruction: " + bc);
        };
    }

    @Override
    public String toString() {
        return String.format("CodeModel[id=%d]", System.identityHashCode(this));
    }
}
