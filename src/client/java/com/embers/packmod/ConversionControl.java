package com.embers.packmod;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class ConversionControl {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    void cancel() { cancelled.set(true); }
    boolean cancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    void check() throws IOException { if (cancelled()) throw new IOException("Conversion cancelled; original retained"); }
}
