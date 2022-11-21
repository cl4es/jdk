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
package java.lang.invoke;

import java.lang.invoke.LambdaForm.Name;
import java.lang.invoke.LambdaForm.NamedFunction;
import java.util.Arrays;

import static java.lang.invoke.LambdaForm.arguments;
import static java.lang.invoke.MethodHandleNatives.Constants.LM_TRUSTED;
import static java.lang.invoke.MethodHandleNatives.Constants.REF_invokeStatic;
import static java.lang.invoke.MethodHandleStatics.traceLambdaForm;
import static java.lang.invoke.MethodHandleStatics.UNSAFE;
import static java.lang.invoke.MethodTypeForm.LF_RESOLVER;

/**
 * Manage resolving LambdaForms, either by lazily spinning up bytecode for them
 * or looking up pregenerated methods that implement them.
 */
class LambdaFormResolvers {

    private static MemberName resolveFrom(String name, MethodType type, Class<?> holder) {
        assert(!UNSAFE.shouldBeInitialized(holder)) : holder + "not initialized";
        MemberName member = new MemberName(holder, name, type, REF_invokeStatic);
        MemberName resolvedMember = MemberName.getFactory().resolveOrNull(REF_invokeStatic, member, holder, LM_TRUSTED);
        traceLambdaForm(name, type, holder, resolvedMember);
        return resolvedMember;
    }

    private static MemberName lookupPregenerated(LambdaForm form, MethodType invokerType) {
        if (form.customized != null) {
            // No pre-generated version for customized LF
            return null;
        }
        String name = form.kind.methodName;
        switch (form.kind) {
            case BOUND_REINVOKER: {
                name = name + "_" + BoundMethodHandle.speciesDataFor(form).key();
                return resolveFrom(name, invokerType, DelegatingMethodHandle.Holder.class);
            }
            case DELEGATE:                  return resolveFrom(name, invokerType, DelegatingMethodHandle.Holder.class);
            case ZERO:                      // fall-through
            case IDENTITY: {
                name = name + "_" + form.returnType().basicTypeChar();
                return resolveFrom(name, invokerType, LambdaForm.Holder.class);
            }
            case EXACT_INVOKER:             // fall-through
            case EXACT_LINKER:              // fall-through
            case LINK_TO_CALL_SITE:         // fall-through
            case LINK_TO_TARGET_METHOD:     // fall-through
            case GENERIC_INVOKER:           // fall-through
            case GENERIC_LINKER:            return resolveFrom(name, invokerType, Invokers.Holder.class);
            case GET_REFERENCE:             // fall-through
            case GET_BOOLEAN:               // fall-through
            case GET_BYTE:                  // fall-through
            case GET_CHAR:                  // fall-through
            case GET_SHORT:                 // fall-through
            case GET_INT:                   // fall-through
            case GET_LONG:                  // fall-through
            case GET_FLOAT:                 // fall-through
            case GET_DOUBLE:                // fall-through
            case PUT_REFERENCE:             // fall-through
            case PUT_BOOLEAN:               // fall-through
            case PUT_BYTE:                  // fall-through
            case PUT_CHAR:                  // fall-through
            case PUT_SHORT:                 // fall-through
            case PUT_INT:                   // fall-through
            case PUT_LONG:                  // fall-through
            case PUT_FLOAT:                 // fall-through
            case PUT_DOUBLE:                // fall-through
            case DIRECT_NEW_INVOKE_SPECIAL: // fall-through
            case DIRECT_INVOKE_INTERFACE:   // fall-through
            case DIRECT_INVOKE_SPECIAL:     // fall-through
            case DIRECT_INVOKE_SPECIAL_IFC: // fall-through
            case DIRECT_INVOKE_STATIC:      // fall-through
            case DIRECT_INVOKE_STATIC_INIT: // fall-through
            case DIRECT_INVOKE_VIRTUAL:     return resolveFrom(name, invokerType, DirectMethodHandle.Holder.class);
        }
        return null;
    }

    public static MemberName resolverFor(LambdaForm form) {
        MethodType basicType = form.methodType();
        // Don't generate a resolver if there's a pregenerated form already
        MemberName name = lookupPregenerated(form, basicType);
        if (name != null) {
            form.vmentry = name;
            return name;
        }
        LambdaForm lform = basicType.form().cachedLambdaForm(LF_RESOLVER);
        if (lform != null) {
            return lform.vmentry;
        }
        MemberName memberName = resolveFrom(LambdaForm.Kind.RESOLVER.methodName, basicType, LambdaFormResolvers.Holder.class);
        if (memberName != null) {
            lform = LambdaForm.createWrapperForResolver(memberName);
        } else {
            lform = makeResolverForm(basicType);
            assert lform.methodType() == form.methodType()
                    : "type mismatch: " + lform.methodType() + " != " + form.methodType();
        }
        lform = basicType.form().setCachedLambdaForm(LF_RESOLVER, lform);
        return lform.vmentry;
    }

    static LambdaForm makeResolverForm(MethodType basicType) {
        final int THIS_MH   = 0;  // the target MH
        final int ARG_BASE  = 1;  // start of incoming arguments
        final int ARG_LIMIT = ARG_BASE + basicType.parameterCount() - 1; // -1 to skip receiver MH

        int nameCursor = ARG_LIMIT;
        final int RESOLVE = nameCursor++;
        final int INVOKE  = nameCursor++;

        Name[] names = arguments(nameCursor - ARG_LIMIT, basicType);

        names[RESOLVE] = new Name(ResolveHolder.NF_resolve, names[THIS_MH]);

        // do not use a basic invoker handle here to avoid max arity problems
        Object[] args = Arrays.copyOf(names, basicType.parameterCount()); // forward all args
        MethodType invokerType = basicType.dropParameterTypes(0, 1); // drop leading MH
        names[INVOKE] = new Name(new NamedFunction(Invokers.invokeBasicMethod(invokerType)), args);

        LambdaForm lform = new LambdaForm(basicType.parameterCount(), names, INVOKE, LambdaForm.Kind.RESOLVER);
        lform.forceCompileToBytecode(); // no cycles, compile this now
        return lform;
    }

    // pre-generated resolvers

    static {
        // The Holder class will contain pre-generated Resolvers resolved
        // speculatively using resolveOrNull. However, that doesn't initialize the class,
        // which subtly breaks inlining etc. By forcing initialization of the Holder
        // class we avoid these issues.
        UNSAFE.ensureClassInitialized(LambdaFormResolvers.Holder.class);
    }

    /* Placeholder class for Resolvers generated ahead of time */
    final class Holder {}

    private static final class ResolveHolder {
        static final NamedFunction NF_resolve;

        static {
            try {
                NF_resolve = new NamedFunction(ResolveHolder.class.getDeclaredMethod("resolve", MethodHandle.class));
            } catch (ReflectiveOperationException e) {
                throw new InternalError(e);
            }
        }

        public static void resolve(MethodHandle mh) {
            mh.form.resolve();
        }
    }

}
