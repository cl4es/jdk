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
import java.util.concurrent.atomic.AtomicBoolean;
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

        Box(T value) {
            this.value = value;
            this.hasValue = true;
        }

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
            return this.hasValue ? Optional.of(this.value) : Optional.empty();
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

    @SuppressWarnings("unchecked")
    @ForceInline
    public static <T> Collector<T,?,Optional<T>> findFirst() {
        return (Collector<T,?,Optional<T>>)FIND_FIRST;
    }

    @SuppressWarnings("unchecked")
    @ForceInline
    public static <T> Collector<T,?,Optional<T>> findLast() {
        return (Collector<T,?,Optional<T>>)FIND_LAST;
    }

    @SuppressWarnings("rawtypes")
    private final static Collector FIND_FIRST =
        Collector.of(Box::new, Box::setIfUnset, Box::preferLeft, Box::optionalValue);

    @SuppressWarnings("rawtypes")
    private final static Collector FIND_LAST =
        Collector.of(Box::new, Box::set, Box::preferRight, Box::optionalValue);

    final static class OfRef<T> implements Stream<T> {
        private final Spliterator<?> source;
        private final Gatherer<?, ?, T> gatherer;
        private Runnable closeHandler;
        private int flags;

        private static final int USED      = (1 << 0);
        private static final int PARALLEL  = (1 << 1);
        private static final int UNORDERED = (1 << 2);

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
            final Spliterator<T> result;
            if (gatherer == identity) {
                ensureUnusedThenUse(); // TODO do we try to propagate flags to the spliterator?
                result = (Spliterator<T>) source;
            } else {
                result = collect(Collectors.toList()).spliterator();
            }
            return result;
        }

        @Override
        public boolean isParallel() { return (flags & PARALLEL) == PARALLEL; }

        @Override
        public OfRef<T> sequential() {
            int f;
            return (((f = flags) & PARALLEL) != PARALLEL) ? this : new OfRef<>(this, this.gatherer, f & ~PARALLEL);
        }

        @Override
        public OfRef<T> parallel() {
            int f;
            return (((f = flags) & PARALLEL) == PARALLEL) ? this : new OfRef<>(this, this.gatherer, f | PARALLEL);
        }

        @Override
        public OfRef<T> unordered() {
            int f;
            return (((f = flags) & UNORDERED) == UNORDERED) ? this : new OfRef<>(this, this.gatherer, f | UNORDERED);
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

        @SuppressWarnings("unchecked")
        private <R, AA, RR> RR gatherCollect(Gatherer<? super T, ?, R> gatherer, Collector<? super R, AA, RR> collector) {
            final int flags = ensureUnusedThenUse();
            Gatherer<?, ?, T> g;
            return evaluate(
                (Spliterator<Object>)this.source,
                flags,
                (Gatherer<Object, Object, R>)(((g = this.gatherer) != identity) ? g.andThen(gatherer) : gatherer),
                collector.supplier(),
                collector.accumulator(),
                ((flags & PARALLEL) == PARALLEL) ? collector.combiner() : null,
                collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)
                    ? null
                    : collector.finisher()
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public <RR, AA> RR collect(Collector<? super T, AA, RR> collector) {
            final int flags = ensureUnusedThenUse();
            return evaluate(
                (Spliterator<Object>)this.source,
                flags,
                (Gatherer<Object, Object, T>)this.gatherer, // TODO special-case the identity gathering case
                collector.supplier(),
                collector.accumulator(),
                ((flags & PARALLEL) == PARALLEL) ? collector.combiner() : null,
                collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)
                    ? null
                    : collector.finisher()
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
                GStream.<Boolean>findFirst()
            ).get();
        }

        @Override
        public boolean allMatch(Predicate<? super T> predicate) {
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
                GStream.findFirst()
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
            class FindOne {
                boolean hasValue;
                T value;
                boolean integrate(T e, Gatherer.Downstream<? super T> d) {
                    return !hasValue && (hasValue = true) && d.push(e);
                }
                FindOne combine(FindOne r) { return hasValue ? this : r; }
                void finish(Gatherer.Downstream<? super T> d) { if (hasValue) d.push(value); }
            }
            return gatherCollect(
                Gatherer.<T, FindOne, T>of(
                    FindOne::new,
                    FindOne::integrate,
                    FindOne::combine,
                    FindOne::finish
                ),
                GStream.findFirst()
            );
        }

        @Override
        public Optional<T> findAny() {
            // TODO if known size is 0, return Optional.empty() immediately
            return findFirst(); // TODO optimize for unordered streams?
        }

        /*
         * evaluate(...) is the primary execution mechanism besides opWrapSink()
         * and implements both sequential, hybrid parallel-sequential, and
         * parallel evaluation
         */
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
            final var integrator = gatherer.integrator();

            // Optimization
            final boolean greedy = integrator instanceof Gatherer.Integrator.Greedy<A, T, R>;

            // Sequential evaluation section starts here.

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
            }

            /*
             * It could be considered to also go to sequential mode if the
             * operation is non-greedy AND the combiner is Gatherer.defaultCombiner()
             * as those operations will not benefit from upstream parallel
             * preprocessing which is the main advantage of the Hybrid evaluation
             * strategy.
             */
            if ((flags & PARALLEL) != PARALLEL)
                return new Sequential().evaluateUsing(spliterator).get();

            // Parallel section starts here:

            final var combiner = gatherer.combiner();

            /*
             * The following implementation of hybrid parallel-sequential
             * Gatherer processing borrows heavily from ForeachOrderedTask,
             * and adds handling of short-circuiting.
             */
            @SuppressWarnings("serial")
            final class Hybrid extends CountedCompleter<Sequential> {
                private final long targetSize;
                private final Hybrid leftPredecessor;
                private final AtomicBoolean cancelled;
                private final Sequential localResult;

                private Spliterator<T> spliterator;
                private Hybrid next;

                private static final VarHandle NEXT = MhUtil.findVarHandle(
                    MethodHandles.lookup(), "next", Hybrid.class);

                protected Hybrid(Spliterator<T> spliterator) {
                    super(null);
                    this.spliterator = spliterator;
                    this.targetSize =
                        AbstractTask.suggestTargetSize(spliterator.estimateSize());
                    this.localResult = new Sequential();
                    this.cancelled = greedy ? null : new AtomicBoolean(false);
                    this.leftPredecessor = null;
                }

                Hybrid(Hybrid parent, Spliterator<T> spliterator, Hybrid leftPredecessor) {
                    super(parent);
                    this.spliterator = spliterator;
                    this.targetSize = parent.targetSize;
                    this.localResult = parent.localResult;
                    this.cancelled = parent.cancelled;
                    this.leftPredecessor = leftPredecessor;
                }

                @Override
                public Sequential getRawResult() {
                    return localResult;
                }

                @Override
                public void setRawResult(Sequential result) {
                    if (result != null) throw new IllegalStateException();
                }

                @Override
                public void compute() {
                    var task = this;
                    Spliterator<T> rightSplit = task.spliterator, leftSplit;
                    long sizeThreshold = task.targetSize;
                    boolean forkRight = false;
                    while ((greedy || !cancelled.get())
                        && rightSplit.estimateSize() > sizeThreshold
                        && (leftSplit = rightSplit.trySplit()) != null) {

                        var leftChild = new Hybrid(task, leftSplit, task.leftPredecessor);
                        var rightChild = new Hybrid(task, rightSplit, leftChild);

                        /* leftChild and rightChild were just created and not
                         * fork():ed yet so no need for a volatile write
                         */
                        leftChild.next = rightChild;

                        // Fork the parent task
                        // Completion of the left and right children "happens-before"
                        // completion of the parent
                        task.addToPendingCount(1);
                        // Completion of the left child "happens-before" completion of
                        // the right child
                        rightChild.addToPendingCount(1);

                        // If task is not on the left spine
                        if (task.leftPredecessor != null) {
                            /*
                             * Completion of left-predecessor, or left subtree,
                             * "happens-before" completion of left-most leaf node of
                             * right subtree.
                             * The left child's pending count needs to be updated before
                             * it is associated in the completion map, otherwise the
                             * left child can complete prematurely and violate the
                             * "happens-before" constraint.
                             */
                            leftChild.addToPendingCount(1);
                            // Update association of left-predecessor to left-most
                            // leaf node of right subtree
                            if (NEXT.compareAndSet(task.leftPredecessor, task, leftChild)) {
                                // If replaced, adjust the pending count of the parent
                                // to complete when its children complete
                                task.addToPendingCount(-1);
                            } else {
                                // Left-predecessor has already completed, parent's
                                // pending count is adjusted by left-predecessor;
                                // left child is ready to complete
                                leftChild.addToPendingCount(-1);
                            }
                        }

                        if (forkRight) {
                            rightSplit = leftSplit;
                            task = leftChild;
                            rightChild.fork();
                        } else {
                            task = rightChild;
                            leftChild.fork();
                        }
                        forkRight = !forkRight;
                    }

                    /*
                     * Task's pending count is either 0 or 1.  If 1 then the completion
                     * map will contain a value that is task, and two calls to
                     * tryComplete are required for completion, one below and one
                     * triggered by the completion of task's left-predecessor in
                     * onCompletion.  Therefore there is no data race within the if
                     * block.
                     *
                     * IMPORTANT: Currently we only perform the processing of this
                     * upstream data if we know the operation is greedy -- as we cannot
                     * safely speculate on the cost/benefit ratio of parallelizing
                     * the pre-processing of upstream data under short-circuiting.
                     */
                    if (greedy && task.getPendingCount() > 0) {
                        // Upstream elements are buffered
                        GathererOp.NodeBuilder<T> nb = new GathererOp.NodeBuilder<>();
                        rightSplit.forEachRemaining(nb); // Run the upstream
                        task.spliterator = nb.build().spliterator();
                    }
                    task.tryComplete();
                }

                @Override
                public void onCompletion(CountedCompleter<?> caller) {
                    var s = spliterator;
                    spliterator = null; // GC assistance

                    /* Performance sensitive since each leaf-task could have a
                     * spliterator of size 1 which means that all else is overhead
                     * which needs minimization.
                     */
                    if (s != null
                        && (greedy || !cancelled.get())
                        && !localResult.evaluateUsing(s).proceed
                        && !greedy)
                        cancelled.set(true);

                    // The completion of this task *and* the dumping of elements
                    // "happens-before" completion of the associated left-most leaf task
                    // of right subtree (if any, which can be this task's right sibling)
                    @SuppressWarnings("unchecked")
                    var leftDescendant = (Hybrid) NEXT.getAndSet(this, null);
                    if (leftDescendant != null) {
                        leftDescendant.tryComplete();
                    }
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
                        cancelLaterTasks();
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
                    if (greedy || (l != null && r != null && l.proceed)) {
                        l.state = combiner.apply(l.state, r.state);
                        l.collectorState =
                            collectorCombiner.apply(l.collectorState, r.collectorState);
                        l.proceed = r.proceed;
                        return l;
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
                        localResult = merge(leftChild.localResult, rightChild.localResult);
                        leftChild = rightChild = null; // GC assistance
                    }
                }

                @SuppressWarnings("unchecked")
                private Parallel getParent() {
                    return (Parallel) getCompleter();
                }

                private boolean isRequestedToCancel() {
                    boolean cancel = canceled;
                    if (!cancel) {
                        for (Parallel parent = getParent();
                             !cancel && parent != null;
                             parent = parent.getParent())
                            cancel = parent.canceled;
                    }
                    return cancel;
                }

                private void cancelLaterTasks() {
                    // Go up the tree, cancel right siblings of this node and all parents
                    for (Parallel parent = getParent(), node = this;
                         parent != null;
                         node = parent, parent = parent.getParent()) {
                        // If node is a left child of parent, then has a right sibling
                        if (parent.leftChild == node)
                            parent.rightChild.canceled = true;
                    }
                }
            }

            if (combiner != Gatherer.defaultCombiner())
                return new Parallel(spliterator).invoke().get();
            else
                return new Hybrid(spliterator).invoke().get();
        }
    }
}
