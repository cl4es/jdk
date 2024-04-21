/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.runtime;

import jdk.internal.vm.annotation.Stable;

import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatFactory;
import java.lang.invoke.TypeDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/**
 * Bootstrap methods for state-driven implementations of core methods,
 * including {@link Object#equals(Object)}, {@link Object#hashCode()}, and
 * {@link Object#toString()}.  These methods may be used, for example, by
 * Java compiler implementations to implement the bodies of {@link Object}
 * methods for record classes.
 *
 * @since 16
 */
public class ObjectMethods {

    private ObjectMethods() { }

    private static final int MAX_STRING_CONCAT_SLOTS = 20;

    private @Stable static MethodHandle TRUE;
    private static MethodHandle trueConstant() {
        MethodHandle mh = TRUE;
        if (mh == null) {
            TRUE = mh = MethodHandles.constant(boolean.class, true);
        }
        return mh;
    }

    private static final MethodHandle CLASS_IS_INSTANCE;
    private static final MethodHandle OBJECTS_EQUALS;
    private static final MethodHandle OBJECTS_HASHCODE;
    private static final MethodHandle OBJECT_EQ;
    private static final MethodHandle HASH_COMBINER;

    private static final HashMap<Class<?>, MethodHandle> primitiveEquals = new HashMap<>();
    private static final HashMap<Class<?>, MethodHandle> primitiveHashers = new HashMap<>();
    private static final HashMap<Class<?>, MethodHandle> primitiveToString = new HashMap<>();

    static {
        try {
            Class<ObjectMethods> OBJECT_METHODS_CLASS = ObjectMethods.class;
            MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> B = byte.class;
            Class<?> C = char.class;
            Class<?> I = int.class;
            Class<?> J = long.class;
            Class<?> S = short.class;
            Class<?> F = float.class;
            Class<?> D = double.class;
            Class<?> Z = boolean.class;

            CLASS_IS_INSTANCE = publicLookup.findVirtual(Class.class, "isInstance",
                                                         methodType(Z, Object.class));
            OBJECTS_EQUALS = publicLookup.findStatic(Objects.class, "equals",
                                                     methodType(Z, Object.class, Object.class));
            OBJECTS_HASHCODE = publicLookup.findStatic(Objects.class, "hashCode",
                                                       methodType(I, Object.class));

            OBJECT_EQ = lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                          methodType(Z, Object.class, Object.class));
            HASH_COMBINER = lookup.findStatic(OBJECT_METHODS_CLASS, "hashCombiner",
                                              methodType(I, I, I));

            primitiveEquals.put(B, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, B, B)));
            primitiveEquals.put(S, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, S, S)));
            primitiveEquals.put(C, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, C, C)));
            primitiveEquals.put(I, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, I, I)));
            primitiveEquals.put(J, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, J, J)));
            primitiveEquals.put(F, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, F, F)));
            primitiveEquals.put(D, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, D, D)));
            primitiveEquals.put(Z, lookup.findStatic(OBJECT_METHODS_CLASS, "eq",
                                                     methodType(Z, Z, Z)));

            primitiveHashers.put(B, lookup.findStatic(Byte.class, "hashCode",
                                                      methodType(I, B)));
            primitiveHashers.put(S, lookup.findStatic(Short.class, "hashCode",
                                                      methodType(I, S)));
            primitiveHashers.put(C, lookup.findStatic(Character.class, "hashCode",
                                                      methodType(I, C)));
            primitiveHashers.put(I, lookup.findStatic(Integer.class, "hashCode",
                                                      methodType(I, I)));
            primitiveHashers.put(J, lookup.findStatic(Long.class, "hashCode",
                                                      methodType(I, J)));
            primitiveHashers.put(F, lookup.findStatic(Float.class, "hashCode",
                                                      methodType(I, F)));
            primitiveHashers.put(D, lookup.findStatic(Double.class, "hashCode",
                                                      methodType(I, D)));
            primitiveHashers.put(Z, lookup.findStatic(Boolean.class, "hashCode",
                                                      methodType(I, Z)));

            primitiveToString.put(B, lookup.findStatic(Byte.class, "toString",
                                                       methodType(String.class, B)));
            primitiveToString.put(S, lookup.findStatic(Short.class, "toString",
                                                       methodType(String.class, S)));
            primitiveToString.put(C, lookup.findStatic(Character.class, "toString",
                                                       methodType(String.class, C)));
            primitiveToString.put(I, lookup.findStatic(Integer.class, "toString",
                                                       methodType(String.class, I)));
            primitiveToString.put(J, lookup.findStatic(Long.class, "toString",
                                                       methodType(String.class, J)));
            primitiveToString.put(F, lookup.findStatic(Float.class, "toString",
                                                       methodType(String.class, F)));
            primitiveToString.put(D, lookup.findStatic(Double.class, "toString",
                                                       methodType(String.class, D)));
            primitiveToString.put(Z, lookup.findStatic(Boolean.class, "toString",
                                                       methodType(String.class, Z)));
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int hashCombiner(int x, int y) {
        return x*31 + y;
    }

    private static boolean eq(Object a, Object b) { return a == b; }
    private static boolean eq(byte a, byte b) { return a == b; }
    private static boolean eq(short a, short b) { return a == b; }
    private static boolean eq(char a, char b) { return a == b; }
    private static boolean eq(int a, int b) { return a == b; }
    private static boolean eq(long a, long b) { return a == b; }
    private static boolean eq(float a, float b) { return Float.compare(a, b) == 0; }
    private static boolean eq(double a, double b) { return Double.compare(a, b) == 0; }
    private static boolean eq(boolean a, boolean b) { return a == b; }

    /** Get the method handle for combining two values of a given type */
    private static MethodHandle equalator(Class<?> clazz) {
        return (clazz.isPrimitive()
                ? primitiveEquals.get(clazz)
                : OBJECTS_EQUALS.asType(methodType(boolean.class, clazz, clazz)));
    }

    /** Get the hasher for a value of a given type */
    private static MethodHandle hasher(Class<?> clazz) {
        return (clazz.isPrimitive()
                ? primitiveHashers.get(clazz)
                : OBJECTS_HASHCODE.asType(methodType(int.class, clazz)));
    }

    /**
     * Generates a method handle for the {@code equals} method for a given data class
     * @param receiverClass   the data class
     * @param getters         the list of getters
     * @return the method handle
     */
    private static MethodHandle makeEquals(Class<?> receiverClass,
                                          List<MethodHandle> getters) {
        MethodType rr = methodType(boolean.class, receiverClass, receiverClass);
        MethodType ro = methodType(boolean.class, receiverClass, Object.class);
        MethodHandle instanceFalse = MethodHandles.dropArguments(MethodHandles.zero(boolean.class), 0, receiverClass, Object.class); // (RO)Z
        MethodHandle instanceTrue = MethodHandles.dropArguments(trueConstant(), 0, receiverClass, Object.class); // (RO)Z
        MethodHandle isSameObject = OBJECT_EQ.asType(ro); // (RO)Z
        MethodHandle isInstance = MethodHandles.dropArguments(CLASS_IS_INSTANCE.bindTo(receiverClass), 0, receiverClass); // (RO)Z
        MethodHandle accumulator = MethodHandles.dropArguments(trueConstant(), 0, receiverClass, receiverClass); // (RR)Z

        for (MethodHandle getter : getters) {
            MethodHandle equalator = equalator(getter.type().returnType()); // (TT)Z
            MethodHandle thisFieldEqual = MethodHandles.filterArguments(equalator, 0, getter, getter); // (RR)Z
            accumulator = MethodHandles.guardWithTest(thisFieldEqual, accumulator, instanceFalse.asType(rr));
        }

        return MethodHandles.guardWithTest(isSameObject,
                                           instanceTrue,
                                           MethodHandles.guardWithTest(isInstance, accumulator.asType(ro), instanceFalse));
    }

    /**
     * Generates a method handle for the {@code hashCode} method for a given data class
     * @param receiverClass   the data class
     * @param getters         the list of getters
     * @return the method handle
     */
    private static MethodHandle makeHashCode(Class<?> receiverClass,
                                            List<MethodHandle> getters) {
        MethodHandle accumulator = MethodHandles.dropArguments(MethodHandles.zero(int.class), 0, receiverClass); // (R)I

        // @@@ Use loop combinator instead?
        for (MethodHandle getter : getters) {
            MethodHandle hasher = hasher(getter.type().returnType()); // (T)I
            MethodHandle hashThisField = MethodHandles.filterArguments(hasher, 0, getter);    // (R)I
            MethodHandle combineHashes = MethodHandles.filterArguments(HASH_COMBINER, 0, accumulator, hashThisField); // (RR)I
            accumulator = MethodHandles.permuteArguments(combineHashes, accumulator.type(), 0, 0); // adapt (R)I to (RR)I
        }

        return accumulator;
    }

    /**
     * Generates a method handle for the {@code toString} method for a given data class
     * @param receiverClass   the data class
     * @param getters         the list of getters
     * @param names           the names
     * @return the method handle
     */
    private static MethodHandle makeToString(MethodHandles.Lookup lookup,
                                            Class<?> receiverClass,
                                            MethodHandle[] getters,
                                            List<String> names) {
        assert getters.length == names.size();
        if (getters.length == 0) {
            // special case
            MethodHandle emptyRecordCase = MethodHandles.constant(String.class, receiverClass.getSimpleName() + "[]");
            emptyRecordCase = MethodHandles.dropArguments(emptyRecordCase, 0, receiverClass); // (R)S
            return emptyRecordCase;
        }

        boolean firstTime = true;
        MethodHandle[] mhs;
        List<List<MethodHandle>> splits;
        MethodHandle[] toSplit = getters;
        int namesIndex = 0;
        do {
            /* StringConcatFactory::makeConcatWithConstants can only deal with 200 slots, longs and double occupy two
             * the rest 1 slot, we need to chop the current `getters` into chunks, it could be that for records with
             * a lot of components that we need to do a couple of iterations. The main difference between the first
             * iteration and the rest would be on the recipe
             */
            splits = split(toSplit);
            mhs = new MethodHandle[splits.size()];
            for (int splitIndex = 0; splitIndex < splits.size(); splitIndex++) {
                StringBuilder recipe = new StringBuilder();
                if (firstTime && splitIndex == 0) {
                    recipe = new StringBuilder(receiverClass.getSimpleName() + "[");
                }
                for (int i = 0; i < splits.get(splitIndex).size(); i++) {
                    if (firstTime) {
                        recipe.append(names.get(namesIndex)).append("=\1");
                    } else {
                        recipe.append('\1');
                    }
                    if (firstTime && namesIndex != names.size() - 1) {
                        recipe.append(", ");
                    }
                    namesIndex++;
                }
                if (firstTime && splitIndex == splits.size() - 1) {
                    recipe.append("]");
                }
                Class<?>[] concatTypeArgs = new Class<?>[splits.get(splitIndex).size()];
                // special case: no need to create another getters if there is only one split
                MethodHandle[] currentSplitGetters = new MethodHandle[splits.get(splitIndex).size()];
                for (int j = 0; j < splits.get(splitIndex).size(); j++) {
                    concatTypeArgs[j] = splits.get(splitIndex).get(j).type().returnType();
                    currentSplitGetters[j] = splits.get(splitIndex).get(j);
                }
                MethodType concatMT = methodType(String.class, concatTypeArgs);
                try {
                    mhs[splitIndex] = StringConcatFactory.makeConcatWithConstants(
                            lookup, "",
                            concatMT,
                            recipe.toString(),
                            new Object[0]
                    ).getTarget();
                    mhs[splitIndex] = MethodHandles.filterArguments(mhs[splitIndex], 0, currentSplitGetters);
                    // this will spread the receiver class across all the getters
                    mhs[splitIndex] = MethodHandles.permuteArguments(
                            mhs[splitIndex],
                            methodType(String.class, receiverClass),
                            new int[splits.get(splitIndex).size()]
                    );
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
            toSplit = mhs;
            firstTime = false;
        } while (splits.size() > 1);
        return mhs[0];
    }

    /**
     * Chops the getters into smaller chunks according to the maximum number of slots
     * StringConcatFactory::makeConcatWithConstants can chew
     * @param getters the current getters
     * @return chunks that won't surpass the maximum number of slots StringConcatFactory::makeConcatWithConstants can chew
     */
    private static List<List<MethodHandle>> split(MethodHandle[] getters) {
        List<List<MethodHandle>> splits = new ArrayList<>();

        int slots = 0;

        // Need to peel, so that neither call has more than acceptable number
        // of slots for the arguments.
        List<MethodHandle> cArgs = new ArrayList<>();
        for (MethodHandle methodHandle : getters) {
            Class<?> returnType = methodHandle.type().returnType();
            int needSlots = (returnType == long.class || returnType == double.class) ? 2 : 1;
            if (slots + needSlots > MAX_STRING_CONCAT_SLOTS) {
                splits.add(cArgs);
                cArgs = new ArrayList<>();
                slots = 0;
            }
            cArgs.add(methodHandle);
            slots += needSlots;
        }

        // Flush the tail slice
        if (!cArgs.isEmpty()) {
            splits.add(cArgs);
        }

        return splits;
    }

    /**
     * Bootstrap method to generate the {@link Object#equals(Object)},
     * {@link Object#hashCode()}, and {@link Object#toString()} methods, based
     * on a description of the component names and accessor methods, for either
     * {@code invokedynamic} call sites or dynamic constant pool entries.
     *
     * For more detail on the semantics of the generated methods see the specification
     * of {@link java.lang.Record#equals(Object)}, {@link java.lang.Record#hashCode()} and
     * {@link java.lang.Record#toString()}.
     *
     *
     * @param lookup       Every bootstrap method is expected to have a {@code lookup}
     *                     which usually represents a lookup context with the
     *                     accessibility privileges of the caller. This is because
     *                     {@code invokedynamic} call sites always provide a {@code lookup}
     *                     to the corresponding bootstrap method, but this method just
     *                     ignores the {@code lookup} parameter
     * @param methodName   the name of the method to generate, which must be one of
     *                     {@code "equals"}, {@code "hashCode"}, or {@code "toString"}
     * @param type         a {@link MethodType} corresponding the descriptor type
     *                     for the method, which must correspond to the descriptor
     *                     for the corresponding {@link Object} method, if linking
     *                     an {@code invokedynamic} call site, or the
     *                     constant {@code MethodHandle.class}, if linking a
     *                     dynamic constant
     * @param recordClass  the record class hosting the record components
     * @param names        the list of component names, joined into a string
     *                     separated by ";", or the empty string if there are no
     *                     components. This parameter is ignored if the {@code methodName}
     *                     parameter is {@code "equals"} or {@code "hashCode"}
     * @param getters      method handles for the accessor methods for the components
     * @return             a call site if invoked by indy, or a method handle
     *                     if invoked by a condy
     * @throws IllegalArgumentException if the bootstrap arguments are invalid
     *                                  or inconsistent
     * @throws NullPointerException if any argument is {@code null} or if any element
     *                              in the {@code getters} array is {@code null}
     * @throws Throwable if any exception is thrown during call site construction
     */
    public static Object bootstrap(MethodHandles.Lookup lookup, String methodName, TypeDescriptor type,
                                   Class<?> recordClass,
                                   String names,
                                   MethodHandle... getters) throws Throwable {
        requireNonNull(lookup);
        requireNonNull(methodName);
        requireNonNull(type);
        requireNonNull(recordClass);
        requireNonNull(names);
        requireNonNull(getters);
        for (MethodHandle getter : getters) {
            requireNonNull(getter);
        }
        MethodType methodType;
        if (type instanceof MethodType mt)
            methodType = mt;
        else {
            methodType = null;
            if (!MethodHandle.class.equals(type))
                throw new IllegalArgumentException(type.toString());
        }
        List<MethodHandle> getterList = List.of(getters);
        MethodHandle handle = switch (methodName) {
            case "equals"   -> {
                if (methodType != null && !methodType.equals(methodType(boolean.class, recordClass, Object.class)))
                    throw new IllegalArgumentException("Bad method type: " + methodType);
                yield makeEquals(recordClass, getterList);
            }
            case "hashCode" -> {
                if (methodType != null && !methodType.equals(methodType(int.class, recordClass)))
                    throw new IllegalArgumentException("Bad method type: " + methodType);
                yield makeHashCode(recordClass, getterList);
            }
            case "toString" -> {
                if (methodType != null && !methodType.equals(methodType(String.class, recordClass)))
                    throw new IllegalArgumentException("Bad method type: " + methodType);
                List<String> nameList = "".equals(names) ? List.of() : List.of(names.split(";"));
                if (nameList.size() != getterList.size())
                    throw new IllegalArgumentException("Name list and accessor list do not match");
                yield makeToString(lookup, recordClass, getters, nameList);
            }
            default -> throw new IllegalArgumentException(methodName);
        };
        return methodType != null ? new ConstantCallSite(handle) : handle;
    }
}
