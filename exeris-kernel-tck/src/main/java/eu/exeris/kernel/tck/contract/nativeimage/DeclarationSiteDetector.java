/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.lang.classfile.Instruction;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.List;

/**
 * Package-private detector matching instructions against flagged reflection, foreign, and loader APIs.
 */
final class DeclarationSiteDetector {

    private DeclarationSiteDetector() {
    }

    // Keep in sync with eu.exeris.kernel.spi.nativeimage.SpiReachabilityMetadataTest.checkInstruction
    static String detectFlaggedApi(Instruction instruction,
                                   List<Instruction> instructions,
                                   int index,
                                   String className,
                                   String methodName) {
        if (instruction instanceof InvokeInstruction inv) {
            String owner = inv.owner().asInternalName();
            String name = inv.name().stringValue();
            return checkInvokeOrRef(owner, name, instructions, index, className, methodName);
        }
        if (instruction instanceof InvokeDynamicInstruction indy) {
            for (LoadableConstantEntry arg : indy.invokedynamic().bootstrap().arguments()) {
                if (arg instanceof MethodHandleEntry mhe) {
                    MemberRefEntry ref = mhe.reference();
                    String owner = ref.owner().asInternalName();
                    String name = ref.name().stringValue();
                    String flagged = checkInvokeOrRef(owner, name, instructions, index, className, methodName);
                    if (flagged != null) {
                        return flagged;
                    }
                }
            }
        }
        if (instruction instanceof ConstantInstruction.LoadConstantInstruction ldc) {
            if (ldc.constantEntry() instanceof MethodHandleEntry mhe) {
                MemberRefEntry ref = mhe.reference();
                String owner = ref.owner().asInternalName();
                String name = ref.name().stringValue();
                String flagged = checkInvokeOrRef(owner, name, instructions, index, className, methodName);
                if (flagged != null) {
                    return flagged;
                }
            }
        } else if (instruction instanceof ConstantInstruction ci
                && ci.constantValue() instanceof DirectMethodHandleDesc dmhd) {
            String desc = dmhd.owner().descriptorString();
            String owner = desc.startsWith("L") && desc.endsWith(";")
                    ? desc.substring(1, desc.length() - 1)
                    : desc;
            String name = dmhd.methodName();
            String flagged = checkInvokeOrRef(owner, name, instructions, index, className, methodName);
            if (flagged != null) {
                return flagged;
            }
        }
        return null;
    }

    private static String checkInvokeOrRef(String owner,
                                           String name,
                                           List<Instruction> instructions,
                                           int index,
                                           String className,
                                           String methodName) {
        if ("java/lang/Class".equals(owner)) {
            return checkClassApi(name);
        }
        if (isClassLoader(owner)) {
            return checkClassLoaderApi(name);
        }
        if ("java/lang/Module".equals(owner) && "getResourceAsStream".equals(name)) {
            return "Module.getResourceAsStream";
        }
        if ("java/lang/invoke/MethodHandles$Lookup".equals(owner)) {
            return checkLookupApi(name, instructions, index, className, methodName);
        }
        if ("java/lang/reflect/Array".equals(owner) && "newInstance".equals(name)) {
            return "Array.newInstance";
        }
        if ("java/lang/reflect/Proxy".equals(owner) && "newProxyInstance".equals(name)) {
            return "Proxy.newProxyInstance";
        }
        if ("java/lang/foreign/Linker".equals(owner) && ("downcallHandle".equals(name) || "upcallStub".equals(name))) {
            return "Linker." + name;
        }
        if ("java/lang/foreign/FunctionDescriptor".equals(owner)) {
            return checkFunctionDescriptorApi(name);
        }
        if ("java/lang/foreign/Linker$Option".equals(owner)) {
            return "Linker$Option." + name;
        }
        if ("java/lang/foreign/Arena".equals(owner) && "ofShared".equals(name)) {
            return "Arena.ofShared";
        }
        return null;
    }

    @SuppressWarnings("PMD.UseProperClassLoader")
    private static boolean isClassLoader(String owner) {
        if ("java/lang/ClassLoader".equals(owner)) {
            return true;
        }
        String binaryName = owner.replace('/', '.');
        try {
            Class<?> clazz = Class.forName(binaryName, false, ClassLoader.getPlatformClassLoader());
            return ClassLoader.class.isAssignableFrom(clazz);
        } catch (ClassNotFoundException | LinkageError e) {
            try {
                Class<?> clazz = Class.forName(binaryName, false, DeclarationSiteDetector.class.getClassLoader());
                return ClassLoader.class.isAssignableFrom(clazz);
            } catch (ClassNotFoundException | LinkageError ignored) {
                return false;
            }
        }
    }

    private static String checkClassApi(String name) {
        if ("forName".equals(name)) {
            return "Class.forName";
        }
        if (name.startsWith("getDeclared")
                || name.startsWith("getMethod")
                || "getMethods".equals(name)
                || name.startsWith("getField")
                || "getFields".equals(name)
                || name.startsWith("getConstructor")
                || "getConstructors".equals(name)
                || name.startsWith("getResource")) {
            return "Class." + name;
        }
        return null;
    }

    private static String checkClassLoaderApi(String name) {
        if ("loadClass".equals(name)
                || name.startsWith("getResource")
                || name.startsWith("getSystemResource")
                || "getResources".equals(name)) {
            return "ClassLoader." + name;
        }
        return null;
    }

    private static String checkLookupApi(String name,
                                         List<Instruction> instructions,
                                         int index,
                                         String className,
                                         String methodName) {
        if (name.startsWith("find") || name.startsWith("unreflect")) {
            if ("findVarHandle".equals(name) && instructions != null && index >= 3) {
                // Ensure literals are extractable, failing loudly on dynamic or non-literal arguments.
                VarHandleTargetExtractor.extract(className, methodName, instructions, index);
            }
            return "MethodHandles$Lookup." + name;
        }
        return null;
    }

    private static String checkFunctionDescriptorApi(String name) {
        if ("of".equals(name)
                || "ofVoid".equals(name)
                || "dropReturnLayout".equals(name)
                || name.startsWith("append")
                || name.startsWith("insert")
                || name.startsWith("change")) {
            return "FunctionDescriptor." + name;
        }
        return null;
    }
}
