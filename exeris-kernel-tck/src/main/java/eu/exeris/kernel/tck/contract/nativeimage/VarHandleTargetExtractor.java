/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.constant.ClassDesc;
import java.util.List;

/**
 * Package-private helper extracting literal arguments from {@code findVarHandle} call sites.
 */
final class VarHandleTargetExtractor {

    private VarHandleTargetExtractor() {
    }

    static DeclarationSiteScanner.VarHandleTarget extract(
            String declaringClass,
            String methodName,
            List<Instruction> instructions,
            int invokeIndex) {
        if (invokeIndex < 3) {
            throw new IllegalStateException("Non-literal findVarHandle in " + declaringClass + "." + methodName
                    + ": insufficient preceding instructions (expected 3 arguments)");
        }
        Instruction arg1 = instructions.get(invokeIndex - 3);
        Instruction arg2 = instructions.get(invokeIndex - 2);
        Instruction arg3 = instructions.get(invokeIndex - 1);

        if (!(arg1 instanceof ConstantInstruction ci1) || !(ci1.constantValue() instanceof ClassDesc cd1)) {
            throw new IllegalStateException("Non-literal findVarHandle receiver in " + declaringClass + "."
                    + methodName + ": expected Class literal but found " + arg1);
        }
        String receiverClass = toClassName(cd1);

        if (!(arg2 instanceof ConstantInstruction ci2) || !(ci2.constantValue() instanceof String fieldName)) {
            throw new IllegalStateException("Non-literal findVarHandle field name in " + declaringClass + "."
                    + methodName + ": expected String literal but found " + arg2);
        }

        String fieldType;
        if (arg3 instanceof ConstantInstruction ci3 && ci3.constantValue() instanceof ClassDesc cd3) {
            fieldType = toClassName(cd3);
        } else if (arg3 instanceof FieldInstruction fi
                && fi.opcode() == Opcode.GETSTATIC
                && "TYPE".equals(fi.name().stringValue())) {
            fieldType = mapBoxType(fi.owner().asInternalName(), declaringClass, methodName);
        } else {
            throw new IllegalStateException("Non-literal findVarHandle field type in " + declaringClass + "."
                    + methodName + ": expected Class literal or Box.TYPE but found " + arg3);
        }

        return new DeclarationSiteScanner.VarHandleTarget(
                declaringClass, methodName, receiverClass, fieldName, fieldType);
    }

    private static String mapBoxType(String boxOwner, String declaringClass, String methodName) {
        return switch (boxOwner) {
            case "java/lang/Integer" -> "int";
            case "java/lang/Long" -> "long";
            case "java/lang/Boolean" -> "boolean";
            case "java/lang/Byte" -> "byte";
            case "java/lang/Character" -> "char";
            case "java/lang/Short" -> "short";
            case "java/lang/Float" -> "float";
            case "java/lang/Double" -> "double";
            case "java/lang/Void" -> "void";
            default -> throw new IllegalStateException("Unsupported primitive wrapper in findVarHandle at "
                    + declaringClass + "." + methodName + ": " + boxOwner);
        };
    }

    static String toClassName(ClassDesc classDesc) {
        if (classDesc.isPrimitive()) {
            return classDesc.displayName();
        }
        String desc = classDesc.descriptorString();
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
        return desc;
    }
}
