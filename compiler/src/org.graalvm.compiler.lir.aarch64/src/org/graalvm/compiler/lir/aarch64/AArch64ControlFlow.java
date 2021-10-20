/*
 * Copyright (c) 2013, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.lir.aarch64;

import static jdk.vm.ci.aarch64.AArch64.lr;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import java.util.function.Consumer;
import java.util.function.Function;

import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.aarch64.AArch64ASIMDAssembler;
import org.graalvm.compiler.asm.aarch64.AArch64Assembler;
import org.graalvm.compiler.asm.aarch64.AArch64Assembler.ConditionFlag;
import org.graalvm.compiler.asm.aarch64.AArch64Assembler.ExtendType;
import org.graalvm.compiler.asm.aarch64.AArch64MacroAssembler;
import org.graalvm.compiler.asm.aarch64.AArch64MacroAssembler.ScratchRegister;
import org.graalvm.compiler.code.CompilationResult.JumpTable;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.core.common.calc.Condition;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.LabelRef;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.StandardOp;
import org.graalvm.compiler.lir.StandardOp.BlockEndOp;
import org.graalvm.compiler.lir.SwitchStrategy;
import org.graalvm.compiler.lir.SwitchStrategy.BaseSwitchClosure;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.Value;

public class AArch64ControlFlow {
    public static final class ReturnOp extends AArch64BlockEndOp implements BlockEndOp {
        public static final LIRInstructionClass<ReturnOp> TYPE = LIRInstructionClass.create(ReturnOp.class);
        @Use({REG, ILLEGAL}) protected Value x;

        public ReturnOp(Value x) {
            super(TYPE);
            this.x = x;
        }

        @Override
        protected void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            crb.frameContext.leave(crb);
            masm.ret(lr);
            crb.frameContext.returned(crb);
        }
    }

    public abstract static class AbstractBranchOp extends AArch64BlockEndOp implements StandardOp.BranchOp {
        private final LabelRef trueDestination;
        private final LabelRef falseDestination;

        private final double trueDestinationProbability;

        private AbstractBranchOp(LIRInstructionClass<? extends AbstractBranchOp> c, LabelRef trueDestination, LabelRef falseDestination, double trueDestinationProbability) {
            super(c);
            this.trueDestination = trueDestination;
            this.falseDestination = falseDestination;
            this.trueDestinationProbability = trueDestinationProbability;
        }

        protected abstract void emitBranch(CompilationResultBuilder crb, AArch64MacroAssembler masm, LabelRef target, boolean negate);

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            /*
             * Explanation: Depending on what the successor edge is, we can use the fall-through to
             * optimize the generated code. If neither is a successor edge, use the branch
             * probability to try to take the conditional jump as often as possible to avoid
             * executing two instructions instead of one.
             */
            if (crb.isSuccessorEdge(trueDestination)) {
                emitBranch(crb, masm, falseDestination, true);
            } else if (crb.isSuccessorEdge(falseDestination)) {
                emitBranch(crb, masm, trueDestination, false);
            } else if (trueDestinationProbability < 0.5) {
                emitBranch(crb, masm, falseDestination, true);
                masm.jmp(trueDestination.label());
            } else {
                emitBranch(crb, masm, trueDestination, false);
                masm.jmp(falseDestination.label());
            }
        }
    }

    public static class BranchOp extends AbstractBranchOp implements StandardOp.BranchOp {
        public static final LIRInstructionClass<BranchOp> TYPE = LIRInstructionClass.create(BranchOp.class);

        private final AArch64Assembler.ConditionFlag condition;

        public BranchOp(AArch64Assembler.ConditionFlag condition, LabelRef trueDestination, LabelRef falseDestination, double trueDestinationProbability) {
            super(TYPE, trueDestination, falseDestination, trueDestinationProbability);
            this.condition = condition;
        }

        @Override
        protected void emitBranch(CompilationResultBuilder crb, AArch64MacroAssembler masm, LabelRef target, boolean negate) {
            AArch64Assembler.ConditionFlag finalCond = negate ? condition.negate() : condition;
            masm.branchConditionally(finalCond, target.label());
        }
    }

    /**
     * Emits either a branch or far branch based on the anticipated branch distance.
     */
    private static void emitBranchOrFarBranch(CompilationResultBuilder crb, AArch64MacroAssembler masm, LIRInstruction instr, int immSize, Label target, Consumer<Label> originalBranch,
                    Consumer<Label> negatedBranch) {

        /* First determine whether a farBranch is necessary. */
        boolean isFarBranch;
        if (target.isBound()) {
            /*
             * If the target is already bound, simply check whether the pc offset can be encoded
             * within instruction.
             */
            isFarBranch = !NumUtil.isSignedNbit(immSize, masm.getPCRelativeOffset(target));
        } else {
            /*
             * If target is not yet bound, then estimate whether target will be reachable.
             *
             * The provided branch instruction can access +-2^(offsetBits-1) bytes from its
             * position. Currently, we estimate that each LIR instruction emits 2 (4-byte) AArch64
             * instructions on average. Hence, we check whether the instruction can be more than
             * 2^(offsetBits-4) LIR instructions away.
             *
             * Note that if we estimate a near branch but the actual target is too far, the
             * assembler will raise a BranchTargetOutOfBoundsException. When this happens, code
             * generation will be restarted in a conservative mode that will always assume far
             * branches.
             */
            int maxLIRDistance = (1 << (immSize - 4));
            isFarBranch = !crb.labelWithinLIRRange(instr, target, maxLIRDistance);
        }

        if (!isFarBranch) {
            /* Target is reachable: can conditionally jump directly to it */
            originalBranch.accept(target);
        } else {
            /*
             * Target is not directly reachable. Must do a direct jump to target and use the negated
             * branch to skip over jump when the target should not be jumped to.
             */
            Label skipJump = new Label();
            negatedBranch.accept(skipJump);
            masm.jmp(target);
            masm.bind(skipJump);
        }
    }

    public static class CompareBranchZeroOp extends AbstractBranchOp implements StandardOp.BranchOp {
        public static final LIRInstructionClass<CompareBranchZeroOp> TYPE = LIRInstructionClass.create(CompareBranchZeroOp.class);

        @Use(REG) private AllocatableValue value;

        public CompareBranchZeroOp(AllocatableValue value, LabelRef trueDestination, LabelRef falseDestination, double trueDestinationProbability) {
            super(TYPE, trueDestination, falseDestination, trueDestinationProbability);
            this.value = value;
        }

        @Override
        protected void emitBranch(CompilationResultBuilder crb, AArch64MacroAssembler masm, LabelRef target, boolean negate) {
            AArch64Kind kind = (AArch64Kind) this.value.getPlatformKind();
            assert kind.isInteger();
            final int size = kind.getSizeInBytes() * Byte.SIZE;
            Consumer<Label> cbzBranch = l -> masm.cbz(size, asRegister(this.value), l);
            Consumer<Label> cbnzBranch = l -> masm.cbnz(size, asRegister(this.value), l);
            Consumer<Label> originalBranch;
            Consumer<Label> negatedBranch;
            if (negate) {
                originalBranch = cbnzBranch;
                negatedBranch = cbzBranch;
            } else {
                originalBranch = cbzBranch;
                negatedBranch = cbnzBranch;
            }
            emitBranchOrFarBranch(crb, masm, this, 21, target.label(), originalBranch, negatedBranch);
        }
    }

    public static class BitTestAndBranchOp extends AbstractBranchOp implements StandardOp.BranchOp {
        public static final LIRInstructionClass<BitTestAndBranchOp> TYPE = LIRInstructionClass.create(BitTestAndBranchOp.class);

        @Use protected AllocatableValue value;
        private final int index;

        public BitTestAndBranchOp(LabelRef trueDestination, LabelRef falseDestination, AllocatableValue value, double trueDestinationProbability, int index) {
            super(TYPE, trueDestination, falseDestination, trueDestinationProbability);
            this.value = value;
            this.index = index;
        }

        @Override
        protected void emitBranch(CompilationResultBuilder crb, AArch64MacroAssembler masm, LabelRef target, boolean negate) {
            Consumer<Label> tbzBranch = l -> masm.tbz(asRegister(this.value), index, l);
            Consumer<Label> tbnzBranch = l -> masm.tbnz(asRegister(this.value), index, l);
            Consumer<Label> originalBranch;
            Consumer<Label> negatedBranch;
            if (negate) {
                originalBranch = tbnzBranch;
                negatedBranch = tbzBranch;
            } else {
                originalBranch = tbzBranch;
                negatedBranch = tbnzBranch;
            }
            emitBranchOrFarBranch(crb, masm, this, 16, target.label(), originalBranch, negatedBranch);
        }
    }

    @Opcode("CMOVE")
    public static class CondMoveOp extends AArch64LIRInstruction {
        public static final LIRInstructionClass<CondMoveOp> TYPE = LIRInstructionClass.create(CondMoveOp.class);

        @Def({REG}) protected AllocatableValue result;
        @Use({REG}) protected AllocatableValue trueValue;
        @Use({REG}) protected AllocatableValue falseValue;
        private final AArch64Assembler.ConditionFlag condition;

        public CondMoveOp(Variable result, AArch64Assembler.ConditionFlag condition, AllocatableValue trueValue, AllocatableValue falseValue) {
            super(TYPE);
            assert trueValue.getPlatformKind() == falseValue.getPlatformKind() && trueValue.getPlatformKind() == result.getPlatformKind();
            this.result = result;
            this.condition = condition;
            this.trueValue = trueValue;
            this.falseValue = falseValue;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            AArch64Kind kind = (AArch64Kind) trueValue.getPlatformKind();
            int size = kind.getSizeInBytes() * Byte.SIZE;
            if (kind.isInteger()) {
                masm.csel(size, asRegister(result), asRegister(trueValue), asRegister(falseValue), condition);
            } else {
                masm.fcsel(size, asRegister(result), asRegister(trueValue), asRegister(falseValue), condition);
            }
        }
    }

    public static class CondSetOp extends AArch64LIRInstruction {
        public static final LIRInstructionClass<CondSetOp> TYPE = LIRInstructionClass.create(CondSetOp.class);

        @Def({REG}) protected AllocatableValue result;
        private final AArch64Assembler.ConditionFlag condition;

        public CondSetOp(Variable result, AArch64Assembler.ConditionFlag condition) {
            super(TYPE);
            this.result = result;
            this.condition = condition;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            int size = result.getPlatformKind().getSizeInBytes() * Byte.SIZE;
            masm.cset(size, asRegister(result), condition);
        }
    }

    public static class StrategySwitchOp extends AArch64BlockEndOp implements StandardOp.BlockEndOp {
        public static final LIRInstructionClass<StrategySwitchOp> TYPE = LIRInstructionClass.create(StrategySwitchOp.class);

        private final Constant[] keyConstants;
        protected final SwitchStrategy strategy;
        private final Function<Condition, ConditionFlag> converter;
        private final LabelRef[] keyTargets;
        private final LabelRef defaultTarget;
        @Use({REG}) protected AllocatableValue key;

        public StrategySwitchOp(SwitchStrategy strategy, LabelRef[] keyTargets, LabelRef defaultTarget, AllocatableValue key,
                        Function<Condition, ConditionFlag> converter) {
            this(TYPE, strategy, keyTargets, defaultTarget, key, converter);
        }

        protected StrategySwitchOp(LIRInstructionClass<? extends StrategySwitchOp> c, SwitchStrategy strategy, LabelRef[] keyTargets, LabelRef defaultTarget, AllocatableValue key,
                        Function<Condition, ConditionFlag> converter) {
            super(c);
            this.strategy = strategy;
            this.converter = converter;
            this.keyConstants = strategy.getKeyConstants();
            this.keyTargets = keyTargets;
            this.defaultTarget = defaultTarget;
            this.key = key;
            assert keyConstants.length == keyTargets.length;
            assert keyConstants.length == strategy.keyProbabilities.length;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            strategy.run(new SwitchClosure(asRegister(key), crb, masm));
        }

        public class SwitchClosure extends BaseSwitchClosure {

            protected final Register keyRegister;
            protected final CompilationResultBuilder crb;
            protected final AArch64MacroAssembler masm;

            protected SwitchClosure(Register keyRegister, CompilationResultBuilder crb, AArch64MacroAssembler masm) {
                super(crb, masm, keyTargets, defaultTarget);
                this.keyRegister = keyRegister;
                this.crb = crb;
                this.masm = masm;
            }

            protected void emitComparison(Constant c) {
                JavaConstant jc = (JavaConstant) c;
                switch (jc.getJavaKind()) {
                    case Int:
                        long lc = jc.asLong();
                        assert NumUtil.isInt(lc);
                        emitCompareHelper(crb, masm, 32, key, jc);
                        break;
                    case Long:
                        emitCompareHelper(crb, masm, 64, key, jc);
                        break;
                    case Object:
                        /* Comparing against ptr */
                        emitCompareHelper(crb, masm, 64, key, jc);
                        break;
                    default:
                        throw new GraalError("switch only supported for int, long and object");
                }
            }

            @Override
            protected void conditionalJump(int index, Condition condition, Label target) {
                emitComparison(keyConstants[index]);
                masm.branchConditionally(converter.apply(condition), target);
            }
        }

        private static void emitCompareHelper(CompilationResultBuilder crb, AArch64MacroAssembler masm, int cmpSize, Value key, JavaConstant jc) {
            assert cmpSize == key.getPlatformKind().getSizeInBytes() * Byte.SIZE;
            long imm = jc.asLong();
            if (AArch64MacroAssembler.isComparisonImmediate(imm)) {
                masm.compare(cmpSize, asRegister(key), NumUtil.safeToInt(imm));
            } else {
                try (ScratchRegister scratch = masm.getScratchRegister()) {
                    Register scratchReg = scratch.getRegister();
                    AArch64Move.const2reg((AArch64Kind) key.getPlatformKind(), crb, masm, scratchReg, jc);
                    masm.cmp(cmpSize, asRegister(key), scratchReg);
                }
            }
        }
    }

    public static final class TableSwitchOp extends AArch64BlockEndOp {
        public static final LIRInstructionClass<TableSwitchOp> TYPE = LIRInstructionClass.create(TableSwitchOp.class);
        private final int lowKey;
        private final LabelRef defaultTarget;
        private final LabelRef[] targets;
        @Use({REG}) protected AllocatableValue index;

        public TableSwitchOp(final int lowKey, final LabelRef defaultTarget, final LabelRef[] targets, AllocatableValue index) {
            super(TYPE);
            this.lowKey = lowKey;
            assert defaultTarget != null;
            this.defaultTarget = defaultTarget;
            this.targets = targets;
            this.index = index;
        }

        /**
         * The goal of this code is to jump to the appropriate destination as specified within a
         * JumpTable, or to the default condition if there is no match within the JumpTable.
         *
         * The JumpTable contains a series of jumps (trampolines) for a contiguous series of indexes
         * in the range [lowKey, highKey]. Finding the appropriate index within the jump table is
         * accomplished in two steps:
         *
         * <ol>
         * <li>Determine whether the index is within the JumpTable. This is accomplished by first
         * normalizing the index (normalizedIdx == index - lowKey), and then checking whether
         * <code>(|normalizedIdx| <= highKey - lowKey</code>). If not, then one must jump to the
         * defaultTarget.</li>
         *
         * <li>If normalizedIdx is with the JumpTable, then jump to JumpTable[normalizedIdx].</li>
         * </ol>
         */
        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            Register indexReg = asRegister(index, AArch64Kind.DWORD);

            try (ScratchRegister scratch1 = masm.getScratchRegister(); ScratchRegister scratch2 = masm.getScratchRegister()) {
                Register scratchReg = scratch1.getRegister();
                Register normalizedIdx = scratch2.getRegister();
                /* Compare index against jump table bounds */
                int highKey = lowKey + targets.length - 1;
                masm.sub(32, normalizedIdx, indexReg, lowKey);
                int keyDiff = highKey - lowKey; // equivalent to targets.length - 1
                if (AArch64MacroAssembler.isComparisonImmediate(keyDiff)) {
                    masm.compare(32, normalizedIdx, keyDiff);
                } else {
                    masm.mov(scratchReg, keyDiff);
                    masm.cmp(32, normalizedIdx, scratchReg);
                }

                // Jump to default target if index is not within the jump table
                masm.branchConditionally(ConditionFlag.HI, defaultTarget.label());

                Label jumpTable = new Label();
                // load start of jump table
                masm.adr(scratchReg, jumpTable);
                // because each jumpTable index is 4 bytes large, shift normalizedIdx by 2.
                masm.add(64, scratchReg, scratchReg, normalizedIdx, ExtendType.UXTW, 2);
                // jump to JumpTable[normalizedIdx]
                masm.jmp(scratchReg);
                masm.bind(jumpTable);
                // emit jump table entries
                for (LabelRef target : targets) {
                    masm.jmp(target.label());
                }
                JumpTable jt = new JumpTable(jumpTable.position(), lowKey, highKey, 4);
                crb.compilationResult.addAnnotation(jt);
            }
        }
    }

    @Opcode("CMOV")
    public static class ASIMDCondMoveOp extends AArch64LIRInstruction {
        public static final LIRInstructionClass<ASIMDCondMoveOp> TYPE = LIRInstructionClass.create(ASIMDCondMoveOp.class);

        @Def({REG}) AllocatableValue result;
        /*
         * For each element, the condition reg is expected to contain either all ones or all zeros
         * to pick between trueVal and falseVal.
         */
        @Use({REG}) AllocatableValue condition;
        /*
         * trueVal & falseVal cannot be assigned the same reg as the result reg, as condition is
         * moved into the result reg before trueVal & falseVal are used.
         */
        @Alive({REG}) AllocatableValue trueVal;
        @Alive({REG}) AllocatableValue falseVal;

        public ASIMDCondMoveOp(AllocatableValue result, AllocatableValue condition, AllocatableValue trueVal, AllocatableValue falseVal) {
            super(TYPE);
            PlatformKind conditionKind = condition.getPlatformKind();
            PlatformKind trueKind = trueVal.getPlatformKind();
            PlatformKind falseKind = falseVal.getPlatformKind();
            assert conditionKind.getSizeInBytes() == trueKind.getSizeInBytes() && conditionKind.getVectorLength() == trueKind.getVectorLength() : condition + " " + trueVal;
            assert trueKind == falseKind : trueVal + " " + falseVal;
            this.result = result;
            this.condition = condition;
            this.trueVal = trueVal;
            this.falseVal = falseVal;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            AArch64ASIMDAssembler.ASIMDSize size = AArch64ASIMDAssembler.ASIMDSize.fromVectorKind(result.getPlatformKind());
            masm.neon.moveVV(size, asRegister(result), asRegister(condition));
            masm.neon.bslVVV(size, asRegister(result), asRegister(trueVal), asRegister(falseVal));
        }
    }
}
