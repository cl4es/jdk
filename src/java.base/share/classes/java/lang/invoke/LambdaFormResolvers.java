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

import jdk.internal.vm.annotation.Hidden;
import sun.invoke.util.Wrapper;

import java.lang.invoke.LambdaForm.Name;
import java.lang.invoke.LambdaForm.NamedFunction;
import java.util.Arrays;

import static java.lang.invoke.LambdaForm.arguments;
import static java.lang.invoke.MethodHandleNatives.Constants.REF_invokeStatic;
import static java.lang.invoke.MethodHandleStatics.TRACE_RESOLVERS;
import static java.lang.invoke.MethodHandleStatics.USE_PRE_GEN_RESOLVERS;
import static java.lang.invoke.MethodHandles.Lookup.IMPL_LOOKUP;

class LambdaFormResolvers {

    private static String getResolverName(MethodType type) {
        String prefix = "resolve_";
        StringBuilder sb = new StringBuilder(prefix.length() + type.parameterCount() + 2);

        sb.append(prefix);
        for (int i = 1; i < type.parameterCount() - 1; i++) {
            Class<?> pt = type.parameterType(i);
            sb.append(getCharType(pt));
        }
        sb.append('_').append(getCharType(type.returnType()));
        return sb.toString();
    }
    private static char getCharType(Class<?> pt) {
        return Wrapper.forBasicType(pt).basicTypeChar();
    }

    public static boolean canResolve(LambdaForm.Kind kind) {
        return kind != LambdaForm.Kind.RESOLVER;
    }

    public static MemberName resolverFor(LambdaForm form) {
        MethodType basicType = form.methodType();

        LambdaForm lform = basicType.form().cachedLambdaForm(MethodTypeForm.LF_RESOLVER);
        if (lform != null) {
            return lform.vmentry;
        }
        if (USE_PRE_GEN_RESOLVERS) {
            MemberName name = findPreGenResolver(basicType);
            if (name != null) {
                return name;
            }
        }

        lform = makeResolverForm(basicType);
        assert lform.methodType() == form.methodType()
                : "type mismatch: " + lform.methodType() + " != " + form.methodType();

        basicType.form().setCachedLambdaForm(MethodTypeForm.LF_RESOLVER, lform);
        return lform.vmentry; // we only care about the bytecode
    }

    private static MemberName findPreGenResolver(MethodType basicType) {
        String resolverName = getResolverName(basicType);
        return IMPL_LOOKUP.resolveOrNull(REF_invokeStatic, LambdaFormResolvers.class, resolverName, basicType);
    }

    private static LambdaForm makeResolverForm(MethodType basicType) {
        if (TRACE_RESOLVERS) {
            System.out.println("[TRACE_RESOLVERS] generating resolver for: " + basicType);
        }

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

    // some pre-generated resolvers

    @LambdaForm.Compiled
    @Hidden
    static void resolve_L_V(Object recv) throws Throwable {
        MethodHandle mh = (MethodHandle) recv;
        mh.form.resolve();
        mh.invokeBasic();
    }

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
