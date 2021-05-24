/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.reflect;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jdk.internal.access.JavaLangInvokeAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;
import jdk.internal.misc.VM;
import sun.security.action.GetPropertyAction;

import static java.lang.invoke.MethodType.methodType;

final class MethodHandleAccessorFactory {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    static MethodAccessorImpl newMethodAccessor(Method method, boolean callerSensitive) {
        assert VM.isJavaLangInvokeInited();

        // ExceptionInInitializerError may be thrown during class initialization
        // Ensure class initialized outside the invocation of method handle
        // so that EIIE is propagated (not wrapped with ITE)
        ensureClassInitialized(method.getDeclaringClass());

        try {
            if (callerSensitive) {
                var dmh = findDirectMethodWithCaller(method);
                if (dmh != null) {
                    return DirectMethodAccessorImpl.callerSensitiveAdapter(method, dmh);
                }
            }
            var dmh = getDirectMethod(method, callerSensitive);
            if (callerSensitive) {
                return DirectMethodAccessorImpl.callerSensitiveMethodAccessor(method, dmh);
            } else {
                return DirectMethodAccessorImpl.methodAccessor(method, dmh);
            }
        } catch (IllegalAccessException e) {
            throw new InternalError(e);
        }
    }

    static ConstructorAccessorImpl newConstructorAccessor(Constructor<?> ctor) {
        assert VM.isJavaLangInvokeInited();

        // ExceptionInInitializerError may be thrown during class initialization
        // Ensure class initialized outside the invocation of method handle
        // so that EIIE is propagated (not wrapped with ITE)
        ensureClassInitialized(ctor.getDeclaringClass());

        try {
            MethodHandle mh = JLIA.unreflectConstructor(ctor);
            int paramCount = mh.type().parameterCount();
            MethodHandle target = mh.asFixedArity();
            MethodType mtype = specializedMethodTypeForConstructor(paramCount);
            if (paramCount > SPECIALIZED_PARAM_COUNT) {
                // spread the parameters only for the non-specialized case
                target = target.asSpreader(Object[].class, paramCount);
            }
            target = target.asType(mtype);
            return DirectConstructorAccessorImpl.constructorAccessor(ctor, target);
        } catch (IllegalAccessException e) {
            throw new InternalError(e);
        }
    }

    static FieldAccessorImpl newFieldAccessor(Field field, boolean isReadOnly) {
        assert VM.isJavaLangInvokeInited();
        try {
            // the declaring class of the field has been initialized
            var varHandle = JLIA.unreflectVarHandle(field);
            Class<?> type = field.getType();
            if (type == Boolean.TYPE) {
                return VarHandleBooleanFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else if (type == Byte.TYPE) {
                return VarHandleByteFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else if (type == Short.TYPE) {
                return VarHandleShortFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else if (type == Character.TYPE) {
                return VarHandleCharacterFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else if (type == Integer.TYPE) {
                return VarHandleIntegerFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else if (type == Long.TYPE) {
                return VarHandleLongFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else if (type == Float.TYPE) {
                return VarHandleFloatFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else if (type == Double.TYPE) {
                return VarHandleDoubleFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            } else {
                return VarHandleObjectFieldAccessorImpl.fieldAccessor(field, varHandle, isReadOnly);
            }
        } catch (IllegalAccessException e) {
            throw new InternalError(e);
        }
    }

    private static MethodHandle getDirectMethod(Method method, boolean callerSensitive) throws IllegalAccessException {
        var mtype = methodType(method.getReturnType(), method.getParameterTypes());
        var isStatic = Modifier.isStatic(method.getModifiers());
        var dmh = isStatic ? JLIA.findStatic(method.getDeclaringClass(), method.getName(), mtype)
                                        : JLIA.findVirtual(method.getDeclaringClass(), method.getName(), mtype);
        if (callerSensitive) {
            // the reflectiveInvoker for caller-sensitive method expects the same signature
            // as Method::invoke i.e. (Object, Object[])Object
            return makeTarget(dmh, isStatic, false);
        }
        return makeSpecializedTarget(dmh, isStatic, false);
    }

    private static MethodHandle findDirectMethodWithCaller(Method method) throws IllegalAccessException {
        String name = method.getName();
        // append a Class parameter
        MethodType mtype = methodType(method.getReturnType(), method.getParameterTypes())
                                .appendParameterTypes(Class.class);
        boolean isStatic = Modifier.isStatic(method.getModifiers());

        MethodHandle dmh = isStatic ? JLIA.findStatic(method.getDeclaringClass(), name, mtype)
                                    : JLIA.findVirtual(method.getDeclaringClass(), name, mtype);
        return dmh != null ? makeSpecializedTarget(dmh, isStatic, true) : null;
    }

    /**
     * Transform the given dmh to a specialized target method handle.
     *
     * If {@code hasCallerParameter} parameter is true, transform the method handle
     * of this method type: {@code (Object, Object[], Class)Object} for the default
     * case.
     *
     * If {@code hasCallerParameter} parameter is false, transform the method handle
     * of this method type: {@code (Object, Object[])Object} for the default case.
     *
     * If the number of formal arguments is small, use a method type specialized
     * the number of formal arguments is 0, 1, and 2, for example, the method type
     * of a static method with one argument can be: {@code (Object)Object}
     *
     * If it's a static method, there is no leading Object parameter.
     *
     * @apiNote
     * This implementation avoids using MethodHandles::catchException to help
     * cold startup performance since this combination is very costly to setup.
     *
     * @param dmh DirectMethodHandle
     * @param isStatic whether given dmh represents static method or not
     * @param hasCallerParameter whether given dmh represents a method with an
     *                         additional caller Class parameter
     * @return transformed dmh to be used as a target in direct method accessors
     */
    static MethodHandle makeSpecializedTarget(MethodHandle dmh, boolean isStatic, boolean hasCallerParameter) {
        MethodHandle target = dmh.asFixedArity();

        // number of formal arguments to the original method (not the adapter)
        // If it is a non-static method, it has a leading `this` argument.
        // Also do not count the caller class argument
        int paramCount = dmh.type().parameterCount() - (isStatic ? 0 : 1) - (hasCallerParameter ? 1 : 0);
        MethodType mtype = specializedMethodType(isStatic, hasCallerParameter, paramCount);
        if (paramCount > SPECIALIZED_PARAM_COUNT) {
            int spreadArgPos = isStatic ? 0 : 1;
            target = target.asSpreader(spreadArgPos, Object[].class, paramCount);
        }
        return target.asType(mtype);
    }

    // specialize for number of formal arguments <= 3 to avoid spreader
    static final int SPECIALIZED_PARAM_COUNT = 3;
    static MethodType specializedMethodType(boolean isStatic, boolean hasCallerParameter, int paramCount) {
        if (isStatic) {
            return switch (paramCount) {
                case 0 -> hasCallerParameter ? methodType(Object.class, Class.class)
                                             : methodType(Object.class);
                case 1 -> hasCallerParameter ? methodType(Object.class, Object.class, Class.class)
                                             : methodType(Object.class, Object.class);
                case 2 -> hasCallerParameter ? methodType(Object.class, Object.class, Object.class, Class.class)
                                             : methodType(Object.class, Object.class, Object.class);
                case 3 -> hasCallerParameter ? methodType(Object.class, Object.class, Object.class, Object.class, Class.class)
                                             : methodType(Object.class, Object.class, Object.class, Object.class);
                default -> hasCallerParameter ? methodType(Object.class, Object[].class, Class.class)
                                              : methodType(Object.class, Object[].class);
            };
        } else {
            return switch (paramCount) {
                case 0 -> hasCallerParameter ? methodType(Object.class, Object.class, Class.class)
                                             : methodType(Object.class, Object.class);
                case 1 -> hasCallerParameter ? methodType(Object.class, Object.class, Object.class, Class.class)
                                             : methodType(Object.class, Object.class, Object.class);
                case 2 -> hasCallerParameter ? methodType(Object.class, Object.class, Object.class, Object.class, Class.class)
                                             : methodType(Object.class, Object.class, Object.class, Object.class);
                case 3 -> hasCallerParameter ? methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Class.class)
                                             : methodType(Object.class, Object.class, Object.class, Object.class, Object.class);
                default -> hasCallerParameter ? methodType(Object.class, Object.class, Object[].class, Class.class)
                                              : methodType(Object.class, Object.class, Object[].class);
            };
        }
    }

    static MethodType specializedMethodTypeForConstructor(int paramCount) {
        return switch (paramCount) {
            case 0 ->  methodType(Object.class);
            case 1 ->  methodType(Object.class, Object.class);
            case 2 ->  methodType(Object.class, Object.class, Object.class);
            case 3 ->  methodType(Object.class, Object.class, Object.class, Object.class);
            default -> methodType(Object.class, Object[].class);
        };
    }

    /**
     * Transforms the given dmh into a target method handle with the method type
     * {@code (Object, Object[])Object} or {@code (Object, Class, Object[])Object}
     */
    static MethodHandle makeTarget(MethodHandle dmh, boolean isStatic, boolean hasCallerParameter) {
        MethodType mtype = hasCallerParameter
                                ? methodType(Object.class, Object.class, Object[].class, Class.class)
                                : methodType(Object.class, Object.class, Object[].class);
        // number of formal arguments
        int paramCount = dmh.type().parameterCount() - (isStatic ? 0 : 1) - (hasCallerParameter ? 1 : 0);
        int spreadArgPos = isStatic ? 0 : 1;
        MethodHandle target = dmh.asFixedArity().asSpreader(spreadArgPos, Object[].class, paramCount);
        if (isStatic) {
            // add leading 'this' parameter to static method which is then ignored
            target = MethodHandles.dropArguments(target, 0, Object.class);
        }
        return target.asType(mtype);
    }

    /**
     * Spins a hidden class that invokes a constant VarHandle of the target field,
     * loaded from the class data via condy, for reliable performance
     */
    static MHFieldAccessor newVarHandleAccessor(Field field, VarHandle varHandle) {
        var name = classNamePrefix(field);
        var cn = name + "$$" + counter.getAndIncrement();
        byte[] bytes = ACCESSOR_CLASSFILES.computeIfAbsent(name, n -> spinByteCode(name, field));
        try {
            var lookup = JLIA.defineHiddenClassWithClassData(LOOKUP, cn, bytes, varHandle, true);
            var ctor = lookup.findConstructor(lookup.lookupClass(), methodType(void.class));
            ctor = ctor.asType(methodType(MHFieldAccessor.class));
            return (MHFieldAccessor) ctor.invokeExact();
        } catch (Throwable e) {
            throw new InternalError(e);
        }
    }

    /**
     * Spins a hidden class that invokes a constant MethodHandle of the target method handle,
     * loaded from the class data via condy, for reliable performance.
     *
     * Due to the overhead of class loading, this is not the default.
     */
    static MHMethodAccessor newMethodHandleAccessor(Method method, MethodHandle target, boolean hasCallerParameter) {
        var name = classNamePrefix(method, target.type(), hasCallerParameter);
        var cn = name + "$$" + counter.getAndIncrement();
        byte[] bytes = ACCESSOR_CLASSFILES.computeIfAbsent(name, n -> spinByteCode(name, method, target.type(), hasCallerParameter));
        try {
            var lookup = JLIA.defineHiddenClassWithClassData(LOOKUP, cn, bytes, target, true);
            var ctor = lookup.findConstructor(lookup.lookupClass(), methodType(void.class));
            ctor = ctor.asType(methodType(MHMethodAccessor.class));
            return (MHMethodAccessor) ctor.invokeExact();
        } catch (Throwable e) {
            throw new InternalError(e);
        }
    }

    /**
     * Spins a hidden class that invokes a constant MethodHandle of the target method handle,
     * loaded from the class data via condy, for reliable performance.
     *
     * Due to the overhead of class loading, this is not the default.
     */
    static MHMethodAccessor newMethodHandleAccessor(Constructor<?> c, MethodHandle target) {
        var name = classNamePrefix(c, target.type());
        var cn = name + "$$" + counter.getAndIncrement();
        byte[] bytes = ACCESSOR_CLASSFILES.computeIfAbsent(name, n -> spinByteCode(name, c, target.type()));
        try {
            var lookup = JLIA.defineHiddenClassWithClassData(LOOKUP, cn, bytes, target, true);
            var ctor = lookup.findConstructor(lookup.lookupClass(), methodType(void.class));
            ctor = ctor.asType(methodType(MHMethodAccessor.class));
            return (MHMethodAccessor) ctor.invokeExact();
        } catch (Throwable e) {
            throw new InternalError(e);
        }
    }

    private static final ConcurrentHashMap<String, byte[]> ACCESSOR_CLASSFILES = new ConcurrentHashMap<>();
    private static final String FIELD_CLASS_NAME_PREFIX = "jdk/internal/reflect/FieldAccessorImpl_";
    private static final String METHOD_CLASS_NAME_PREFIX = "jdk/internal/reflect/MethodAccessorImpl_";
    private static final String CONSTRUCTOR_CLASS_NAME_PREFIX = "jdk/internal/reflect/ConstructorAccessorImpl_";

    // Used to ensure that each spun class name is unique
    private static final AtomicInteger counter = new AtomicInteger();

    private static String classNamePrefix(Field field) {
        var isStatic = Modifier.isStatic(field.getModifiers());
        var type = field.getType();
        var desc = type.isPrimitive() ? type.descriptorString() : "L";
        return FIELD_CLASS_NAME_PREFIX + (isStatic ? desc : "L" + desc);
    }
    private static String classNamePrefix(Method method, MethodType mtype, boolean hasCallerParameter) {
        var isStatic = Modifier.isStatic(method.getModifiers());
        var methodTypeName = methodTypeName(isStatic, hasCallerParameter, mtype);
        return METHOD_CLASS_NAME_PREFIX + methodTypeName;
    }
    private static String classNamePrefix(Constructor<?> c, MethodType mtype) {
        var methodTypeName = methodTypeName(true, false, mtype);
        return CONSTRUCTOR_CLASS_NAME_PREFIX + methodTypeName;
    }

    /**
     * Returns a string to represent the specialized method type.
     */
    private static String methodTypeName(boolean isStatic, boolean hasCallerParameter, MethodType mtype) {
        StringBuilder sb = new StringBuilder();
        int pIndex = 0;
        if (!isStatic) {
            sb.append("L");
            pIndex++;
        }
        int paramCount = mtype.parameterCount() - (hasCallerParameter ? 1 : 0);
        for (;pIndex < paramCount; pIndex++) {
            Class<?> ptype = mtype.parameterType(pIndex);
            if (ptype == Object[].class) {
                sb.append("A");
            } else {
                assert ptype == Object.class;
                sb.append("L");
            }
        }
        if (hasCallerParameter) {
            sb.append("Class");
            pIndex++;
        }
        return sb.toString();
    }

    private static byte[] spinByteCode(String cn, Field field) {
        var builder = new ClassByteBuilder(cn, VarHandle.class);
        var bytes = builder.buildFieldAccessor(field);
        maybeDumpClassFile(cn, bytes);
        return bytes;
    }
    private static byte[] spinByteCode(String cn, Method method, MethodType mtype, boolean hasCallerParameter) {
        var builder = new ClassByteBuilder(cn, MethodHandle.class);
        var bytes = builder.buildMethodAccessor(method, mtype, hasCallerParameter);
        maybeDumpClassFile(cn, bytes);
        return bytes;
    }
    private static byte[] spinByteCode(String cn, Constructor<?> ctor, MethodType mtype) {
        var builder = new ClassByteBuilder(cn, MethodHandle.class);
        var bytes = builder.buildConstructorAccessor(ctor, mtype);
        maybeDumpClassFile(cn, bytes);
        return bytes;
    }
    private static void maybeDumpClassFile(String classname, byte[] bytes) {
        if (DUMP_CLASS_FILES != null) {
            try {
                Path p = DUMP_CLASS_FILES.resolve(classname + ".class");
                Files.createDirectories(p.getParent());
                try (OutputStream os = Files.newOutputStream(p)) {
                    os.write(bytes);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Ensures the given class is initialized.  If this is called from <clinit>,
     * this method returns but defc's class initialization is not completed.
     */
    static void ensureClassInitialized(Class<?> defc) {
        if (UNSAFE.shouldBeInitialized(defc)) {
            UNSAFE.ensureClassInitialized(defc);
        }
    }

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final JavaLangInvokeAccess JLIA = SharedSecrets.getJavaLangInvokeAccess();
    private static final Path DUMP_CLASS_FILES;

    static {
        String dumpPath = GetPropertyAction.privilegedGetProperty("jdk.reflect.dumpClassPath");
        if (dumpPath != null) {
            DUMP_CLASS_FILES = Path.of(dumpPath);
        } else {
            DUMP_CLASS_FILES = null;
        }
    }
}

