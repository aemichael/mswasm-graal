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
package org.graalvm.compiler.replacements.amd64;

import static org.graalvm.compiler.api.directives.GraalDirectives.LIKELY_PROBABILITY;
import static org.graalvm.compiler.api.directives.GraalDirectives.UNLIKELY_PROBABILITY;
import static org.graalvm.compiler.api.directives.GraalDirectives.injectBranchProbability;
import static org.graalvm.compiler.replacements.ReplacementsUtil.charArrayBaseOffset;
import static org.graalvm.compiler.replacements.ReplacementsUtil.charArrayIndexScale;

import org.graalvm.compiler.api.replacements.ClassSubstitution;
import org.graalvm.compiler.api.replacements.Fold.InjectedParameter;
import org.graalvm.compiler.api.replacements.MethodSubstitution;
import org.graalvm.compiler.graph.Node.ConstantNodeParameter;
import org.graalvm.compiler.replacements.ArrayIndexOf;
import org.graalvm.compiler.replacements.nodes.ArrayRegionEqualsNode;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;

// JaCoCo Exclude

/**
 * Substitutions for {@link java.lang.String} methods.
 */
@ClassSubstitution(String.class)
public class AMD64StringSubstitutions {

    /** Marker value for the {@link InjectedParameter} injected parameter. */
    static final MetaAccessProvider INJECTED = null;

    // Only exists in JDK <= 8
    @MethodSubstitution(isStatic = true, optional = true)
    public static int indexOf(char[] source, int sourceOffset, int sourceCount,
                    @ConstantNodeParameter char[] target, int targetOffset, int targetCount,
                    int origFromIndex) {
        int fromIndex = origFromIndex;
        if (injectBranchProbability(UNLIKELY_PROBABILITY, fromIndex >= sourceCount)) {
            return (targetCount == 0 ? sourceCount : -1);
        }
        if (injectBranchProbability(UNLIKELY_PROBABILITY, fromIndex < 0)) {
            fromIndex = 0;
        }
        if (injectBranchProbability(UNLIKELY_PROBABILITY, targetCount == 0)) {
            // The empty string is in every string.
            return fromIndex;
        }

        int totalOffset = sourceOffset + fromIndex;
        if (injectBranchProbability(UNLIKELY_PROBABILITY, sourceCount - fromIndex < targetCount)) {
            // The empty string contains nothing except the empty string.
            return -1;
        }

        if (injectBranchProbability(UNLIKELY_PROBABILITY, targetCount == 1)) {
            return ArrayIndexOf.indexOfC1S2(source, sourceCount, totalOffset, target[targetOffset]);
        } else {
            int haystackLength = sourceCount - (targetCount - 2);
            while (injectBranchProbability(LIKELY_PROBABILITY, totalOffset < haystackLength)) {
                int indexOfResult = ArrayIndexOf.indexOfTwoConsecutiveCS2(source, haystackLength, totalOffset, target[targetOffset], target[targetOffset + 1]);
                if (injectBranchProbability(UNLIKELY_PROBABILITY, indexOfResult < 0)) {
                    return -1;
                }
                totalOffset = indexOfResult;
                if (injectBranchProbability(UNLIKELY_PROBABILITY, targetCount == 2)) {
                    return totalOffset;
                } else {
                    long srcOffset = charArrayBaseOffset(INJECTED) + ((long) totalOffset * charArrayIndexScale(INJECTED));
                    long tgtOffset = charArrayBaseOffset(INJECTED) + ((long) targetOffset * charArrayIndexScale(INJECTED));
                    if (injectBranchProbability(UNLIKELY_PROBABILITY, ArrayRegionEqualsNode.regionEquals(source, srcOffset, target, tgtOffset, targetCount, JavaKind.Char))) {
                        return totalOffset;
                    }
                }
                totalOffset++;
            }
            return -1;
        }
    }
}
