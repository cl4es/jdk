/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

package java.util.stream;

import jdk.internal.access.SharedSecrets;
import jdk.internal.vm.annotation.ForceInline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CountedCompleter;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

final class GStream {

    private GStream() { throw new UnsupportedOperationException(); }

    private final static class Cell<T> {
        T value;
        boolean hasValue;

        Cell() {}

        void setIfUnset(T value) {
            if (!this.hasValue) {
                this.value = value;
                this.hasValue = true;
            }
        }

        void set(T value) {
            this.value = value;
            if (!this.hasValue)
                this.hasValue = true;
        }

        Cell<T> obtrude(T value) {
            this.value = value;
            return this;
        }

        Cell<T> preferRight(Cell<T> right) {
            return right.hasValue ? right : this;
        }

        Cell<T> preferLeft(Cell<T> right) {
            return this.hasValue ? this : right;
        }

        Optional<T> optionalValue() {
            return this.hasValue ? Optional.ofNullable(this.value) : Optional.empty();
        }

        @Override
        public String toString() {
            return "Cell[value=" + value + ", hasValue=" + hasValue + "]";
        }
    }

    private static final Gatherer<Object, Void, Object> identity =
        Gatherer.of(Gatherer.Integrator.ofGreedy((v, e, d) -> d.push(e)));

    @SuppressWarnings("unchecked")
    @ForceInline
    private static <T> Gatherer<T, ?, T> identity() {
        return (Gatherer<T, ?, T>) identity;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @ForceInline
    public static <T> Collector<T, ?, Optional<T>> findFirst() {
        return (Collector<T, ?, Optional<T>>)(Collector)FIND_FIRST;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @ForceInline
    public static <T> Collector<T, ?, Optional<T>> findLast() {
        return (Collector<T, ?, Optional<T>>)(Collector)FIND_LAST;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @ForceInline
    public static <T> Collector<? super T, SpinedBuffer<T>, SpinedBuffer<T>> buffer() {
        return (Collector<? super T, SpinedBuffer<T>, SpinedBuffer<T>>)(Collector)BUFFER;
    }

    private final static Collector<? super Object, Cell<Object>, Optional<Object>> FIND_FIRST =
        Collector.of(Cell::new, Cell::setIfUnset, Cell::preferLeft, Cell::optionalValue);

    private final static Collector<? super Object, Cell<Object>, Optional<Object>> FIND_LAST =
        Collector.of(Cell::new, Cell::set, Cell::preferRight, Cell::optionalValue);

    // TODO consider different buffering technique
    private static final Collector<? super Object, SpinedBuffer<Object>, SpinedBuffer<Object>> BUFFER =
        Collector.of(
            SpinedBuffer::new,
            SpinedBuffer::accept,
            (SpinedBuffer<Object> l, SpinedBuffer<Object> r) -> {
                r.forEach(l);
                return l;
            },
            Collector.Characteristics.IDENTITY_FINISH
        );


    final static class OfRef<T> implements Stream<T> {
        private final Object source; // Either a Spliterator or a Supplier<Spliterator>
        private final Gatherer<?, ?, T> gatherer;
        private Runnable closeHandler;
        private int flags;

        private static final int DEFERRED   = (1 << 0);
        private static final int USED       = (1 << 1);
        private static final int PARALLEL   = (1 << 2);
        private static final int UNORDERED  = (1 << 3);

        OfRef(Spliterator<? extends T> spliterator) {
            //assert spliterator != null;
            this.source   = spliterator;
            this.gatherer = identity();
            //this.closeHandler = null;
            //this.flags  = 0; // FIXME translate Spliterator characteristics?
        }

        // TODO This is an experiment of segmented
        // The idea is to package the evaluation of the previous segment
        // as a Supplier<Spliterator<T>> using a SpinedBuffer to hold the
        // intermediate values and then feed them into the current segment
        OfRef(OfRef<T> upstream) {
            // evaluation
            //assert upstream != null;
            //assert upstream.flags & USED == USED;
            @SuppressWarnings("unchecked")
            Supplier<Spliterator<T>> supplier = () -> {
                if (upstream.gatherer == identity) {
                    return upstream.resolveSpliterator();
                } else {
                    @SuppressWarnings("rawtypes")
                    IntFunction rawGenerator = Object[]::new;
                    var into = GStream.<T>buffer();
                    return Arrays.spliterator(
                        (T[])evaluate(
                            upstream.resolveSpliterator(),
                            upstream.flags,
                            upstream.gatherer,
                            into.supplier(),
                            into.accumulator(),
                            into.combiner(),
                            into.finisher()
                        ).asArray(rawGenerator)
                    );
                }
            };

            this.source   = supplier;
            this.gatherer = identity();
            this.closeHandler = upstream.closeHandler; // We need to make sure
            this.flags  = upstream.flags | DEFERRED;
        }

        private OfRef(OfRef<?> upstream, Gatherer<?, ?, T> gatherer, int flags) {
            //assert upstream != null;
            //assert gatherer != null;
            this.source       = upstream.source;
            this.closeHandler = upstream.closeHandler;
            this.flags        = flags;
            this.gatherer     = gatherer;
        }

        private final int ensureUnusedThenUse() {
            int f;
            if (((f = flags) & USED) == USED)
                throw new IllegalStateException("Stream has already been composed or executed");
            flags |= USED;
            return f;
        }

        @Override
        public Iterator<T> iterator() { return Spliterators.iterator(spliterator()); }

        @Override
        @SuppressWarnings("unchecked")
        public Spliterator<T> spliterator() {
            if (gatherer == identity) {
                ensureUnusedThenUse();
                return resolveSpliterator();
            } else {
                return (Spliterator<T>) Arrays.spliterator(toArray()); // FIXME better impl
            }
        }

        @SuppressWarnings("unchecked")
        private <X> Spliterator<X> resolveSpliterator() {
            return ((flags & DEFERRED) != DEFERRED)
                ? (Spliterator<X>) source
                : ((Supplier<Spliterator<X>>)source).get();
        }

        @Override
        public boolean isParallel() { return (flags & PARALLEL) == PARALLEL; }

        @Override
        public OfRef<T> sequential() {
            int f;
            return (((f = flags) & PARALLEL) != PARALLEL)
                ? this
                : new OfRef<>(this, this.gatherer, f & ~PARALLEL);
        }

        @Override
        public OfRef<T> parallel() {
            int f;
            return (((f = flags) & PARALLEL) == PARALLEL)
                ? this
                : new OfRef<>(this, this.gatherer, f | PARALLEL);
        }

        @Override
        public OfRef<T> unordered() {
            int f;
            return (((f = flags) & UNORDERED) == UNORDERED)
                ? this
                : new OfRef<>(this, this.gatherer, f | UNORDERED);
        }

        @Override
        public OfRef<T> onClose(Runnable closeHandler) {
            Objects.requireNonNull(closeHandler, "closeHandler");
            ensureUnusedThenUse();
            Runnable current;
            this.closeHandler = (current = this.closeHandler) != null
                ? Streams.composeWithExceptions(current, closeHandler)
                : closeHandler;
            return this;
        }

        @Override
        public void close() {
            this.flags |= USED;
            var close = closeHandler;
            this.closeHandler = null;
            if (close != null)
                close.run();
        }

        @Override
        public OfRef<T> filter(Predicate<? super T> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            return gather(
                Gatherer.of(
                    Gatherer.Integrator.ofGreedy(
                        (v, e, d) -> !predicate.test(e) || d.push(e)
                    )
                )
            );
        }

        @Override
        public <R> OfRef<R> map(Function<? super T, ? extends R> mapper) {
            Objects.requireNonNull(mapper, "mapper");

            return gather(
                Gatherer.of(
                    Gatherer.Integrator.ofGreedy(
                        (v, e, d) -> d.push(mapper.apply(e))
                    )
                )
            );
        }

        @Override
        public <R> OfRef<R> gather(Gatherer<? super T, ?, R> gatherer) {
            Objects.requireNonNull(gatherer, "gatherer");
            var f = ensureUnusedThenUse();
            Gatherer<?, ?, T> g;
            return new OfRef<>(this, (g = this.gatherer) != identity ? g.andThen(gatherer) : gatherer, f);
        }

        @Override
        public IntStream mapToInt(ToIntFunction<? super T> mapper) { throw new UnsupportedOperationException(); } // FIXME Implement

        @Override
        public LongStream mapToLong(ToLongFunction<? super T> mapper) { throw new UnsupportedOperationException(); } // FIXME Implement

        @Override
        public DoubleStream mapToDouble(ToDoubleFunction<? super T> mapper) { throw new UnsupportedOperationException(); } // FIXME Implement

        @Override
        public <R> OfRef<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return gather(
                Gatherer.of(
                    (v, e, d) -> {
                        try (var s = mapper.apply(e)) {
                            return s == null || s.sequential().allMatch(d::push);
                        }
                    }
                )
            );
        }

        @Override public IntStream flatMapToInt(Function<? super T, ? extends IntStream> mapper) { throw new UnsupportedOperationException(); }

        @Override public LongStream flatMapToLong(Function<? super T, ? extends LongStream> mapper) { throw new UnsupportedOperationException(); }

        @Override public DoubleStream flatMapToDouble(Function<? super T, ? extends DoubleStream> mapper) { throw new UnsupportedOperationException(); }

        @Override public <R> Stream<R> mapMulti(BiConsumer<? super T, ? super Consumer<R>> mapper) {
            Objects.requireNonNull(mapper, "mapper");
            return gather(
                Gatherer.of(
                    (v, e, d) -> {
                        mapper.accept(e, (Consumer<R>) d::push);
                        return !d.isRejecting(); // Best we can do
                    }
                )
            );
        }

        @Override public IntStream mapMultiToInt(BiConsumer<? super T, ? super IntConsumer> mapper) { return Stream.super.mapMultiToInt(mapper); }

        @Override public LongStream mapMultiToLong(BiConsumer<? super T, ? super LongConsumer> mapper) { return Stream.super.mapMultiToLong(mapper); }

        @Override public DoubleStream mapMultiToDouble(BiConsumer<? super T, ? super DoubleConsumer> mapper) { return Stream.super.mapMultiToDouble(mapper); }

        @Override
        public OfRef<T> distinct() {
            return gather(
                Gatherer.of(
                    LinkedHashSet<T>::new,
                    Gatherer.Integrator.ofGreedy((a,e,c) -> a.add(e) || true),
                    (l,r) -> { l.addAll(r); return l; },
                    (a,c) -> a.stream().allMatch(c::push)
                )
            );
        }

        @Override @SuppressWarnings("unchecked") public OfRef<T> sorted() { return sorted((Comparator<? super T>) Comparator.naturalOrder()); }

        @Override
        public OfRef<T> sorted(Comparator<? super T> comparator) {
            Objects.requireNonNull(comparator, "comparator");
            return gather(
                Gatherer.ofSequential(
                    ArrayList<T>::new,
                    Gatherer.Integrator.ofGreedy((a, e, c) -> a.add(e)), // FIXME throw on overflow
                    (a, c) -> { a.sort(comparator); a.stream().allMatch(c::push); }
                )
            );
        }

        @Override public OfRef<T> peek(Consumer<? super T> action) {
            Objects.requireNonNull(action, "action");
            return gather(
                Gatherer.of(
                    Gatherer.Integrator.ofGreedy(
                        (v, e, d) -> { action.accept(e); return d.push(e); }
                    )
                )
            );
        }

        @Override public OfRef<T> limit(long maxSize) {
            if (maxSize < 1)
                throw new IllegalArgumentException(Long.toString(maxSize));

            final class Limit {
                long left = maxSize;

                boolean integrate(T e, Gatherer.Downstream<? super T> d) {
                    long l;
                    return ((l = left) != 0) && (d.push(e) & (left = l - 1) != 0);
                }
            }

            return gather(
                Gatherer.<T, Limit, T>ofSequential(
                    Limit::new,
                    Limit::integrate
                )
            );
        }

        @Override public OfRef<T> skip(long n) {
            if (n < 0)
                throw new IllegalArgumentException(Long.toString(n));

            if (n == 0)
                return this;

            final class Skip {
                long left = n;

                boolean integrate(T e, Gatherer.Downstream<? super T> d) {
                    long l;
                    return ((l = left) != 0 && (left = l - 1) != l) || d.push(e);
                }
            }

            return gather(
                Gatherer.ofSequential(
                    Skip::new,
                    Gatherer.Integrator.<Skip,T,T>ofGreedy(Skip::integrate)
                )
            );
        }

        @Override public OfRef<T> takeWhile(Predicate<? super T> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            return gather(
                Gatherer.of(
                    (v, e, d) -> predicate.test(e) && d.push(e)
                )
            );
        }

        @Override public OfRef<T> dropWhile(Predicate<? super T> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            final class DropWhile {
                boolean drop = true;

                boolean integrate(T e, Gatherer.Downstream<? super T> d) {
                    return (drop && (drop = predicate.test(e))) || d.push(e);
                }
            }
            return gather(
                Gatherer.<T, DropWhile, T>ofSequential(
                    DropWhile::new,
                    DropWhile::integrate
                )
            );
        }

        @Override public void forEach(Consumer<? super T> action) {
            collect(
                new Collectors.CollectorImpl<>(
                    Gatherer.defaultInitializer(),
                    (v, e) -> action.accept(e),
                    Gatherers.Value.DEFAULT.statelessCombiner,
                    Set.of()
                )
            );
        }

        @SuppressWarnings("unchecked")
        @Override public void forEachOrdered(Consumer<? super T> action) {
            sequential()
                .collect(
                    new Collectors.CollectorImpl<>(
                        Gatherer.defaultInitializer(),
                        (v, e) -> action.accept(e),
                        Gatherer.defaultCombiner(), // Shouldn't be used since we drop Parallel
                        Set.of()
                    )
                ); // TODO potentially fuse these operations
        }

        @Override public Object[] toArray() {
            return toArray(Object[]::new);
        }

        @SuppressWarnings("unchecked")
        @Override public <A> A[] toArray(IntFunction<A[]> generator) {
            // Since A has no relation to U (not possible to declare that A is an upper bound of U)
            // there will be no static type checking.
            // Therefore use a raw type and assume A == U rather than propagating the separation of A and U
            // throughout the code-base.
            // The runtime type of U is never checked for equality with the component type of the runtime type of A[].
            // Runtime checking will be performed when an element is stored in A[], thus if A is not a
            // super type of U an ArrayStoreException will be thrown.
            @SuppressWarnings("rawtypes")
            IntFunction rawGenerator = generator;
            return (A[])collect(GStream.buffer()).asArray(rawGenerator);
        }

        @Override
        public T reduce(T identity, BinaryOperator<T> accumulator) { return reduce(identity, accumulator, accumulator); }

        @Override
        public Optional<T> reduce(BinaryOperator<T> accumulator) {
            Objects.requireNonNull(accumulator, "accumulator");
            final class Reducer {
                boolean hasValue;
                T value;

                void accumulate(T v) {
                    value = hasValue || !(hasValue = true) ? accumulator.apply(value, v) : v;
                }

                Reducer combine(Reducer right) {
                    if (right.hasValue)
                        accumulate(right.value);
                    return this;
                }

                Optional<T> value() {
                    return hasValue ? Optional.ofNullable(value) : Optional.empty();
                }
            }

            return collect(
                new Collectors.CollectorImpl<>(
                    Reducer::new,
                    Reducer::accumulate,
                    Reducer::combine,
                    Reducer::value,
                    Set.of()
                )
            );
        }

        @Override
        public <U> U reduce(U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
            Objects.requireNonNull(accumulator, "accumulator");
            Objects.requireNonNull(combiner, "combiner");
            final class Reducer {
                U value = identity;
                void accumulate(T v) {
                    value = accumulator.apply(value, v);
                }

                Reducer combine(Reducer right) {
                    this.value = combiner.apply(this.value, right.value);
                    return this;
                }

                U value() {
                    return this.value;
                }
            }

            return collect(
                new Collectors.CollectorImpl<>(
                    Reducer::new,
                    Reducer::accumulate,
                    Reducer::combine,
                    Reducer::value,
                    Collectors.CH_NOID
                )
            );
        }

        @Override
        public <R> R collect(Supplier<R> supplier, BiConsumer<R, ? super T> accumulator, BiConsumer<R, R> combiner) {
            Objects.requireNonNull(combiner, "combiner");
            return collect(
                new Collectors.CollectorImpl<>(
                    supplier,
                    accumulator,
                    (l, r) -> { combiner.accept(l, r); return l; },
                    Collectors.CH_ID
                )
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public <RR, AA> RR collect(Collector<? super T, AA, RR> collector) {
            final int f = ensureUnusedThenUse();
            return evaluate(
                resolveSpliterator(),
                f,
                (Gatherer<Object, ?, T>) this.gatherer,
                collector.supplier(),
                collector.accumulator(),
                (f & PARALLEL) == PARALLEL ? collector.combiner() : null,
                collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)
                    ? null
                    : collector.finisher()
            );
        }

        @Override
        public List<T> toList() {
            // TODO if known size is 0, return List.empty() immediately
            return SharedSecrets.getJavaUtilCollectionAccess().listFromTrustedArrayNullsAllowed(this.toArray());
        }

        @Override
        public Optional<T> min(Comparator<? super T> comparator) {
            // TODO if known size is 0, return Optional.empty() immediately
            return reduce(BinaryOperator.minBy(comparator));
        }

        @Override
        public Optional<T> max(Comparator<? super T> comparator) {
            // TODO if known size is 0, return Optional.empty() immediately
            return reduce(BinaryOperator.maxBy(comparator));
        }

        @Override
        public long count() {
            // TODO optimize based on size
            return collect(Collectors.counting());
        }

        @Override
        public boolean anyMatch(Predicate<? super T> predicate) {
            // TODO if known size is 0, return false immediately
            class AnyMatch {
                boolean match = false;
                boolean integrate(T e, Gatherer.Downstream<? super Boolean> d) {
                    return !(match || (match |= predicate.test(e)));
                }
                AnyMatch combine(AnyMatch r) { return this.match ? this : r; }
                void finish(Gatherer.Downstream<? super Boolean> d) { d.push(match); }
            }
            return gather(
                        Gatherer.<T, AnyMatch, Boolean>of(
                            AnyMatch::new,
                            AnyMatch::integrate,
                            AnyMatch::combine,
                            AnyMatch::finish
                        )
                    )
                .collect(GStream.findFirst()).get(); // TODO potentially fuse these operations
        }

        @Override
        public boolean allMatch(Predicate<? super T> predicate) {
            Objects.requireNonNull(predicate, "predicate");
            // TODO if known size is 0, return true immediately
            class AllMatch {
                boolean match = true;
                boolean integrate(T e, Gatherer.Downstream<? super Boolean> d) {
                    return match && (match &= predicate.test(e));
                }
                AllMatch combine(AllMatch r) { return !match ? this : r; }
                void finish(Gatherer.Downstream<? super Boolean> d) { d.push(match); }
            }
            return gather(
                    Gatherer.<T, AllMatch, Boolean>of(
                        AllMatch::new,
                        AllMatch::integrate,
                        AllMatch::combine,
                        AllMatch::finish
                    )
                )
                .collect(GStream.findFirst())
                .get(); // TODO potentially fuse these operations
        }

        @Override
        public boolean noneMatch(Predicate<? super T> predicate) {
            // TODO if known size is 0, return false immediately
            return allMatch(predicate.negate()); // Implicit null-check of predicate
        }

        @Override
        public Optional<T> findFirst() {
            // TODO if known size is 0, return Optional.empty() immediately
            final int f = ensureUnusedThenUse();
            return gather(Gatherer.<T,T>of((v, e, d) -> d.push(e) & false))
                .collect(GStream.findFirst()); // TODO potentially fuse these operations
        }

        @Override
        public Optional<T> findAny() {
            // TODO if known size is 0, return Optional.empty() immediately
            return unordered() // TODO should we force this or defer to user?
                .gather(Gatherer.<T,T>of((v, e, d) -> d.push(e) & false))
                .collect(GStream.findLast()); // TODO potentially fuse these operations
        }

        // FIXME this is just an idea of how we could run a stream in segments
        OfRef<T> happensBefore() {
            final int f = ensureUnusedThenUse();
            return new OfRef<>(this);
        }

        /*
         * evaluate(...) is the primary execution mechanism besides opWrapSink()
         * and implements both sequential, hybrid parallel-sequential, and
         * parallel evaluation
         */
        @SuppressWarnings("unchecked")
        private static <T, A, R, CA, CR> CR evaluate(
            final Spliterator<T> spliterator,
            final int flags,
            final Gatherer<T, A, R> gatherer,
            final Supplier<CA> collectorSupplier,
            final BiConsumer<CA, ? super R> collectorAccumulator,
            final BinaryOperator<CA> collectorCombiner,
            final Function<CA, CR> collectorFinisher) {

            // There are two main sections here: sequential and parallel

            final var initializer = gatherer.initializer();
            final var integrator  = gatherer.integrator();
            final var combiner    = gatherer.combiner();
            final var finisher    = gatherer.finisher();

            // Optimization
            final boolean greedy = integrator instanceof Gatherer.Integrator.Greedy;
            // FIXME UNORDERED support
            final boolean unordered = (flags & UNORDERED) == UNORDERED;

            // Sequential is the fusion of a Gatherer and a Collector which can
            // be evaluated sequentially.
            final class Sequential implements Consumer<T>, Gatherer.Downstream<R> {
                A state;
                CA collectorState;
                boolean proceed;

                Sequential() {
                    if (initializer != Gatherer.defaultInitializer())
                        state = initializer.get();
                    if (collectorSupplier != Gatherer.defaultInitializer())
                        collectorState = collectorSupplier.get();
                    proceed = true;
                }

                @ForceInline
                Sequential evaluateUsing(Spliterator<T> spliterator) {
                    if (greedy)
                        spliterator.forEachRemaining(this);
                    else
                        do {
                        } while (proceed && spliterator.tryAdvance(this));

                    return this;
                }

                /*
                 * No need to override isKnownDone() as the default is `false`
                 * and collectors can never short-circuit.
                 */
                @Override
                public boolean push(R r) {
                    collectorAccumulator.accept(collectorState, r);
                    return true;
                }

                @Override
                public void accept(T t) {
                    /*
                     * Benchmarking has shown that, in this case, conditional
                     * writing of `proceed` is desirable  and if that was not the
                     *  case, then the following line would've been clearer:
                     *
                     * proceed &= integrator.integrate(state, t, this);
                     */
                    var ignore =
                        integrator.integrate(state, t, this) || greedy || (proceed = false);
                }

                @SuppressWarnings("unchecked")
                public CR get() {
                    if (finisher != Gatherer.<A, R>defaultFinisher())
                        finisher.accept(state, this);
                    // IF collectorFinisher == null -> IDENTITY_FINISH
                    return (collectorFinisher == null)
                        ? (CR) collectorState
                        : collectorFinisher.apply(collectorState);
                }

                @Override
                public String toString() {
                    return "Sequential[collectorState=" + collectorState + ", proceed=" + proceed + "]";
                }
            }

            /*
             * The following implementation of parallel Gatherer processing
             * borrows heavily from AbstractShortCircuitTask
             */
            @SuppressWarnings("serial")
            final class Parallel extends CountedCompleter<Sequential> {
                private Spliterator<T> spliterator;
                private Parallel leftChild; // Only non-null if rightChild is
                private Parallel rightChild; // Only non-null if leftChild is
                private Sequential localResult;
                private volatile boolean canceled;
                private long targetSize; // lazily initialized

                private Parallel(Parallel parent, Spliterator<T> spliterator) {
                    super(parent);
                    this.targetSize = parent.targetSize;
                    this.spliterator = spliterator;
                }

                Parallel(Spliterator<T> spliterator) {
                    super(null);
                    this.targetSize = 0L;
                    this.spliterator = spliterator;
                }

                private long getTargetSize(long sizeEstimate) {
                    long s;
                    return ((s = targetSize) != 0
                        ? s
                        : (targetSize = AbstractTask.suggestTargetSize(sizeEstimate)));
                }

                @Override
                public Sequential getRawResult() {
                    return localResult;
                }

                @Override
                public void setRawResult(Sequential result) {
                    if (result != null) throw new IllegalStateException();
                }

                private void doProcess() {
                    if (!(localResult = new Sequential()).evaluateUsing(spliterator).proceed
                        && !greedy)
                        cancelTasks();
                }

                @Override
                public void compute() {
                    Spliterator<T> rs = spliterator, ls;
                    long sizeEstimate = rs.estimateSize();
                    final long sizeThreshold = getTargetSize(sizeEstimate);
                    Parallel task = this;
                    boolean forkRight = false;
                    boolean proceed;
                    while ((proceed = (greedy || !task.isRequestedToCancel()))
                        && sizeEstimate > sizeThreshold
                        && (ls = rs.trySplit()) != null) {
                        final var leftChild = task.leftChild = new Parallel(task, ls);
                        final var rightChild = task.rightChild = new Parallel(task, rs);
                        task.setPendingCount(1);
                        if (forkRight) {
                            rs = ls;
                            task = leftChild;
                            rightChild.fork();
                        } else {
                            task = rightChild;
                            leftChild.fork();
                        }
                        forkRight = !forkRight;
                        sizeEstimate = rs.estimateSize();
                    }
                    if (proceed)
                        task.doProcess();
                    task.tryComplete();
                }

                Sequential merge(Sequential l, Sequential r) {
                    /*
                     * Only join the right if the left side didn't short-circuit,
                     * or when greedy
                     */
                    if (greedy || (l != null && r != null && (unordered != l.proceed))) {
                        l.state = combiner.apply(l.state, r.state); // FIXME conditional?
                        l.collectorState = collectorCombiner.apply(l.collectorState, r.collectorState);
                        l.proceed &= r.proceed;
                    }

                    return (l != null) ? l : r;
                }

                @Override
                public void onCompletion(CountedCompleter<?> caller) {
                    spliterator = null; // GC assistance
                    if (leftChild != null) {
                        /* Results can only be null in the case where there's
                         * short-circuiting or when Gatherers are stateful but
                         * uses `null` as their state value.
                         */
                        //assert localResult == null;
                        localResult = merge(leftChild.localResult, rightChild.localResult);
                        leftChild = rightChild = null; // GC assistance
                    }
                }

                @SuppressWarnings("unchecked")
                private Parallel getParent() {
                    return (Parallel) getCompleter();
                }

                @SuppressWarnings("unchecked")
                public Parallel getParallelRoot() {
                    return (Parallel) getRoot();
                }

                private boolean isRequestedToCancel() {
                    boolean cancel;
                    if (!(cancel = canceled)) {
                        for (Parallel parent = getParent();
                             parent != null && !(cancel = parent.canceled);
                             parent = parent.getParent()) {}
                    }
                    return cancel;
                }

                @SuppressWarnings("unchecked")
                private void cancelTasks() {
                    if (unordered) {
                        // If we are unordered we just cancel left and right
                        // as we do not need to respect encounter order
                        getParallelRoot().canceled = true;
                        // TODO is it worth traversing the graph and cancelling each node?
                    } else {
                        for (Parallel p = getParent(), node = this;
                             p != null;
                             node = p, p = p.getParent()) {
                            // If node is a left child of parent, then has a right sibling
                            // which means that if we cancel that, we cancel tasks
                            // processing elements *later* in the stream order
                            if (p.leftChild == node)
                                p.rightChild.canceled = true;
                        }
                    }
                }
            }

            /*
             * It could be considered to also go to sequential mode if the
             * operation is non-greedy AND the combiner is Gatherer.defaultCombiner()
             * as those operations will not benefit from upstream parallel
             * preprocessing which is the main advantage of the Hybrid evaluation
             * strategy.
             */
            return ((flags & PARALLEL) != PARALLEL || combiner == Gatherer.defaultCombiner())
                ? new Sequential().evaluateUsing(spliterator).get() // TODO validate EA
                : new Parallel(spliterator).invoke().get();
        }
    }
}
