/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import java.util.List;
import java.util.function.UnaryOperator;

// CommentDefaultAccessModifier: package-private bootstrap helper is intentionally scoped to this package.
@SuppressWarnings({"java:S2201", "PMD.CommentDefaultAccessModifier"})
final class CommunityCarrierBindings {

    private CommunityCarrierBindings() {
    }

    static <T> Binding<T> binding(ScopedValue<T> key, T value) {
        return new Binding<>(key, value);
    }

    @SafeVarargs
    static UnaryOperator<ScopedValue.Carrier> operator(Binding<?>... bindings) {
        return operator(List.of(bindings));
    }

    static UnaryOperator<ScopedValue.Carrier> operator(List<Binding<?>> bindings) {
        UnaryOperator<ScopedValue.Carrier> operator = UnaryOperator.identity();
        for (Binding<?> binding : bindings) {
            UnaryOperator<ScopedValue.Carrier> previous = operator;
            UnaryOperator<ScopedValue.Carrier> next = binding.operator();
            operator = carrier -> next.apply(previous.apply(carrier));
        }
        return operator;
    }

    record Binding<T>(ScopedValue<T> key, T value) {
        @SuppressWarnings("java:S2201")
        private UnaryOperator<ScopedValue.Carrier> operator() {
            return carrier -> carrier.where(key, value); // NOSONAR: returns a carrier for bootstrap
        }
    }
}