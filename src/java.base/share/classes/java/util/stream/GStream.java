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

import jdk.internal.invoke.MhUtil;
import jdk.internal.vm.annotation.ForceInline;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeoutException;
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

    private final static class Box<T> {
        T value;
        boolean hasValue;

        Box() {}

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

        Box<T> obtrude(T value) {
            this.value = value;
            return this;
        }

        Box<T> preferRight(Box<T> right) {
            return right.hasValue ? right : this;
        }

        Box<T> preferLeft(Box<T> right) {
            return this.hasValue ? this : right;
        }

        Optional<T> optionalValue() {
            return this.hasValue ? Optional.ofNullable(this.value) : Optional.empty();
        }

        @Override
        public String toString() {
            return "Box[value=" + value + ", hasValue=" + hasValue + "]";
        }
    }

    private static final Gatherer<Object, Void, Object> identity =
        Gatherer.of(Gatherer.Integrator.ofGreedy((v, e, d) -> d.push(e)));

    @SuppressWarnings("unchecked")
    @ForceInline
    private static <T> Gatherer<?, ?, T> identity() {
        return (Gatherer<?, ?, T>) identity;
    }

    private enum IntoTheVoid
        implements Collector<Object, Void, Void>,
                   Supplier<Void>,
                   BiConsumer<Void, Object>,
                   Function<Void, Void> {
        INSTANCE;

        @ForceInline
        static final <T> Collector<? super T, Void, Void> instance() {
            return INSTANCE;
        }

        private static final Set<Characteristics> ID_FINISH = Set.of(Characteristics.IDENTITY_FINISH);

        @Override public Supplier<Void> supplier() { return this; }
        @Override public BiConsumer<Void, Object> accumulator() { return this; }
        @Override public BinaryOperator<Void> combiner() { return Gatherers.Value.DEFAULT.statelessCombiner; }
        @Override public Function<Void, Void> finisher() { return this; }
        @Override public Set<Characteristics> characteristics() { return ID_FINISH; }

        @Override public void accept(Void unused, Object o) { }
        @Override public Void apply(Void unused) { return null; }
        @Override public Void get() { return null; }
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

    private final static Collector<? super Object, Box<Object>, Optional<Object>> FIND_FIRST =
        Collector.of(Box::new, Box::setIfUnset, Box::preferLeft, Box::optionalValue);

    private final static Collector<? super Object, Box<Object>, Optional<Object>> FIND_LAST =
        Collector.of(Box::new, Box::set, Box::preferRight, Box::optionalValue);

    final static class OfRef<T> implements Stream<T> {
        private final Spliterator<?> source;
        private final Gatherer<?, ?, T> gatherer;
        private Runnable closeHandler;
        private int flags;

        private static final int USED       = (1 << 0);
        private static final int PARALLEL   = (1 << 1);
        private static final int UNORDERED  = (1 << 2);

        OfRef(Spliterator<? extends T> spliterator) {
            //assert spliterator != null;
            this.source   = spliterator;
            this.gatherer = identity();
            this.flags    = 0;
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
                ensureUnusedThenUse(); // TODO do we try to propagate flags to the spliterator?
                return (Spliterator<T>) source;
            } else {
                return collect(Collectors.toList()).spliterator(); // FIXME better impl
            }
        }

        @Override
        public boolean isParallel() { return (flags & PARALLEL) == PARALLEL; }

        @Override
        public OfRef<T> sequential() {
            int f;
            return (((f = flags) & PARALLEL) == 0)
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

        OfRef<T> peekOrdered(Consumer<? super T> action) {
            Objects.requireNonNull(action, "action");
            return gather(
                Gatherer.ofSequential(
                    Gatherer.Integrator.ofGreedy(
                        (v, e, d) -> { action.accept(e); return d.push(e); }
                    )
                )
            );
        }

        @Override public OfRef<T> limit(long maxSize) {
            if (maxSize < 1)
                throw new IllegalArgumentException(Long.toString(maxSize));

            class Limit {
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

            class Skip {
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
            class DropWhile {
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
            peek(action).collect(IntoTheVoid.instance()); // TODO optimize
        }

        @Override public void forEachOrdered(Consumer<? super T> action) {
            peekOrdered(action).collect(IntoTheVoid.instance()); // TODO optimize
        }

        @Override public Object[] toArray() { return toList().toArray(); } // FIXME implement

        @Override public <A> A[] toArray(IntFunction<A[]> generator) { return toList().toArray(generator); } // FIXME implement

        @Override
        public T reduce(T identity, BinaryOperator<T> accumulator) { return reduce(identity, accumulator, accumulator); }

        @Override
        public Optional<T> reduce(BinaryOperator<T> accumulator) {
            return collect(
                Collector.of(
                    () -> new Box<T>(),
                    (b, e) -> b.value = b.hasValue || !(b.hasValue = true) ? accumulator.apply(b.value, e) : e,
                    (b1, b2) ->
                        b2.hasValue
                            ? b1.hasValue
                                ? b1.obtrude(accumulator.apply(b1.value, b2.value))
                                : b2
                            : b1,
                    Box::optionalValue
                )
            );
        }

        @Override
        public <U> U reduce(U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
            class Reducer {
                U value = identity;
                void accumulate(T value) {
                    this.value = accumulator.apply(this.value, value);
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
                Collector.of(
                    Reducer::new,
                    Reducer::accumulate,
                    Reducer::combine,
                    Reducer::value
                )
            );
        }

        @Override
        public <R> R collect(Supplier<R> supplier, BiConsumer<R, ? super T> accumulator, BiConsumer<R, R> combiner) {
            return collect(
                Collector.of(
                    supplier,
                    accumulator,
                    (l,r) -> { combiner.accept(l,r); return l; },
                    Collector.Characteristics.IDENTITY_FINISH
                )
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public <RR, AA> RR collect(Collector<? super T, AA, RR> collector) {
            final int f = ensureUnusedThenUse();
            return evaluate(
                (Spliterator<Object>)this.source,
                (Gatherer<Object, Object, T>)this.gatherer,
                collector,
                f
            );
        }

        @Override
        public List<T> toList() {
            // TODO if known size is 0, return List.empty() immediately
            return collect(Collectors.toUnmodifiableList());
        }

        @Override
        public Optional<T> min(Comparator<? super T> comparator) {
            // TODO if known size is 0, return Optional.empty() immediately
            return collect(Collectors.minBy(comparator));
        }

        @Override
        public Optional<T> max(Comparator<? super T> comparator) {
            // TODO if known size is 0, return Optional.empty() immediately
            return collect(Collectors.maxBy(comparator));
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
            return gatherCollect(
                Gatherer.<T, AnyMatch, Boolean>of(
                    AnyMatch::new,
                    AnyMatch::integrate,
                    AnyMatch::combine,
                    AnyMatch::finish
                ),
                GStream.findFirst(),
                0
            ).get();
        }

        @Override
        public boolean allMatch(Predicate<? super T> predicate) {
            final int f = ensureUnusedThenUse();
            // TODO if known size is 0, return true immediately
            class AllMatch {
                boolean match = true;
                boolean integrate(T e, Gatherer.Downstream<? super Boolean> d) {
                    return match && (match &= predicate.test(e));
                }
                AllMatch combine(AllMatch r) { return !match ? this : r; }
                void finish(Gatherer.Downstream<? super Boolean> d) { d.push(match); }
            }
            return gatherCollect(
                Gatherer.<T, AllMatch, Boolean>of(
                    AllMatch::new,
                    AllMatch::integrate,
                    AllMatch::combine,
                    AllMatch::finish
                ),
                GStream.findFirst(),
                f
            ).get();
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
            return gatherCollect(
                Gatherer.of((v, e, d) -> d.push(e) & false),
                GStream.findFirst(),
                f
            );
        }

        @Override
        public Optional<T> findAny() {
            final int f = ensureUnusedThenUse();
            // TODO if known size is 0, return Optional.empty() immediately
            return gatherCollect(
                Gatherer.of((v, e, d) -> d.push(e) & false),
                GStream.findLast(),
                f | UNORDERED // Add the unordered flag
            );
        }

        @SuppressWarnings("unchecked")
        private <R, RR> RR gatherCollect(Gatherer<? super T, ?, R> gatherer,
                                         Collector<? super R, ?, RR> collector,
                                         int flags) {
            Gatherer<?, ?, T> g;
            return evaluate(
                (Spliterator<Object>) this.source,
                (Gatherer<Object, ?, R>)(((g = this.gatherer) != identity) ? g.andThen(gatherer) : gatherer),
                collector,
                flags
            );
        }

        public static <T, A, R, AA, RR> RR evaluate(
            Spliterator<T> spliterator,
            Gatherer<T, A, R> gatherer,
            Collector<? super R, AA, RR> collector,
            int flags) {
            return evaluate(
                spliterator,
                flags,
                gatherer,
                collector.supplier(),
                collector.accumulator(),
                (flags & PARALLEL) == PARALLEL ? collector.combiner() : null,
                collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)
                    ? null
                    : collector.finisher()
            );
        }

        @SuppressWarnings("unchecked")
        private static <R, CA, CR> CR evaluate(
            final Spliterator<R> spliterator,
            final int flags,
            final Supplier<CA> collectorSupplier,
            final BiConsumer<CA, ? super R> collectorAccumulator,
            final BinaryOperator<CA> collectorCombiner,
            final Function<CA, CR> collectorFinisher) {
            if ((flags & PARALLEL) != PARALLEL) {
                var state = collectorSupplier.get();
                spliterator.forEachRemaining(r -> collectorAccumulator.accept(state, r));
                return collectorFinisher != null ? collectorFinisher.apply(state) : (CR)state;
            } else {
                @SuppressWarnings("serial")
                final class Parallel extends CountedCompleter<CR> implements Consumer<R> {
                    private Spliterator<R> spliterator;
                    private Parallel leftChild; // Only non-null if rightChild is
                    private Parallel rightChild; // Only non-null if leftChild is
                    private CA localResult;
                    private volatile boolean canceled;
                    private long targetSize; // lazily initialized

                    private Parallel(Parallel parent, Spliterator<R> spliterator) {
                        super(parent);
                        this.targetSize = parent.targetSize;
                        this.spliterator = spliterator;
                    }

                    Parallel(Spliterator<R> spliterator) {
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
                    public CR getRawResult() {
                        return collectorFinisher != null
                            ? collectorFinisher.apply(localResult)
                            : (CR)localResult;
                    }

                    @Override
                    public void setRawResult(CR result) {
                        if (result != null) throw new IllegalStateException();
                    }

                    @Override
                    public void accept(R r) {
                        collectorAccumulator.accept(localResult, r);
                    }

                    private void doProcess() {
                        localResult = collectorSupplier.get();
                        spliterator.forEachRemaining(this);
                    }

                    @Override
                    public void compute() {
                        Spliterator<R> rs = spliterator, ls;
                        long sizeEstimate = rs.estimateSize();
                        final long sizeThreshold = getTargetSize(sizeEstimate);
                        Parallel task = this;
                        boolean forkRight = false;
                        while (sizeEstimate > sizeThreshold
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
                        task.doProcess();
                        task.tryComplete();
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
                            localResult = collectorCombiner.apply(leftChild.localResult, rightChild.localResult);
                            leftChild = rightChild = null; // GC assistance
                        }
                    }
                }
                return new Parallel(spliterator).invoke();
            }
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

            // Disregard the Gatherer if it is identity
            if (gatherer == identity)
                return evaluate(
                    (Spliterator<R>)spliterator,
                    flags,
                    collectorSupplier,
                    collectorAccumulator,
                    collectorCombiner, collectorFinisher
                );

            // There are two main sections here: sequential and parallel

            final var initializer = gatherer.initializer();
            final var integrator  = gatherer.integrator();
            final var combiner    = gatherer.combiner();

            // Optimization
            final boolean greedy = integrator instanceof Gatherer.Integrator.Greedy<A, T, R>;
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

                    var ignore = integrator.integrate(state, t, this)
                        || (!greedy && (proceed = false));
                }

                @SuppressWarnings("unchecked")
                public CR get() {
                    final var finisher = gatherer.finisher();
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
                        l.state = combiner.apply(l.state, r.state); // FIXME conditional call?
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
            return ((flags & PARALLEL) == 0 || combiner == Gatherer.defaultCombiner())
                ? new Sequential().evaluateUsing(spliterator).get() // TODO validate EA
                : new Parallel(spliterator).invoke().get();
        }
    }
}
