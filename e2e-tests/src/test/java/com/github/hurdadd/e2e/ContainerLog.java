package com.github.hurdadd.e2e;

import org.testcontainers.containers.output.OutputFrame;

import java.util.function.Consumer;

/** Streams a container's output to stderr so a container that dies on startup explains itself. */
final class ContainerLog implements Consumer<OutputFrame> {
    @Override
    public void accept(OutputFrame frame) {
        System.err.print(frame.getUtf8String());
    }
}
