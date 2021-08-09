package org.bench.transports.aeron;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.agrona.DirectBuffer;

import java.util.function.Consumer;

@Slf4j
@AllArgsConstructor
public class ProtoBufFragmentHandler implements FragmentHandler {

    private final Consumer<MessageLite> onEventConsumer;
    private final Parser<? extends MessageLite> parser;

    @Override
    public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        final byte[] data = new byte[length];
        buffer.getBytes(offset, data);
        try {
            final var msg = parser.parseFrom(data);
            onEventConsumer.accept(msg);
        } catch (InvalidProtocolBufferException ex) {
            log.error("unable to parse protobuf message from buffer", ex);
        }
    }
}
