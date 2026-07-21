package com.electrahub.paymentgateway.service;

import java.util.OptionalLong;

/**
 * Supplies a shared configuration generation for route-cache entries.
 *
 * <p>A missing generation deliberately prevents cache use. The resolver then
 * queries the durable configuration store, which is safer than reusing a
 * potentially stale route after a control-plane change.</p>
 */
public interface RouteCacheGeneration {

    OptionalLong current();

    void advance();
}
